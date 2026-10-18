package net.aethermc.ffa;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQLite storage. One connection guarded by a lock; heavy work runs on a dedicated single-thread
 * executor so the main thread never blocks except for explicitly synchronous calls (startup, rare admin ops).
 */
public final class Db {
    @FunctionalInterface public interface SqlFn<T> { T apply(Connection c) throws SQLException; }

    private final AetherFFA plugin;
    private final File file;
    private Connection conn;
    private final Object lock = new Object();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AetherFFA-DB"); t.setDaemon(true); return t; });
    private final AtomicLong failures = new AtomicLong();
    private volatile long lastLatencyMs = -1;
    private volatile String lastError = "";

    public Db(AetherFFA plugin, File file) { this.plugin = plugin; this.file = file; }

    public void open() throws SQLException {
        synchronized (lock) {
            try { Class.forName("org.sqlite.JDBC"); }
            catch (ClassNotFoundException e) { throw new SQLException("SQLite JDBC driver not found on this server (Paper normally bundles it).", e); }
            reconnect();
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT, balance INTEGER NOT NULL DEFAULT 0,"
                        + "kills INTEGER NOT NULL DEFAULT 0, deaths INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0,"
                        + "best_streak INTEGER NOT NULL DEFAULT 0, bounty_earned INTEGER NOT NULL DEFAULT 0,"
                        + "bounties_claimed INTEGER NOT NULL DEFAULT 0, effect TEXT, death_msg TEXT, effects_on INTEGER NOT NULL DEFAULT 1,"
                        + "created INTEGER, last_seen INTEGER)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_players_name ON players(name COLLATE NOCASE)");
                s.execute("CREATE TABLE IF NOT EXISTS player_kv(uuid TEXT NOT NULL, k TEXT NOT NULL, v TEXT, PRIMARY KEY(uuid,k))");
                s.execute("CREATE TABLE IF NOT EXISTS transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, delta INTEGER NOT NULL,"
                        + "balance_after INTEGER NOT NULL, reason TEXT, ref TEXT UNIQUE, ts INTEGER NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_tx_uuid ON transactions(uuid, ts DESC)");
                s.execute("CREATE TABLE IF NOT EXISTS pending_payouts(ref TEXT PRIMARY KEY, uuid TEXT NOT NULL, amount INTEGER NOT NULL, reason TEXT, created INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS teams(id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE, leader TEXT NOT NULL, created INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS team_members(uuid TEXT PRIMARY KEY, team_id TEXT NOT NULL, joined INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS bounties(id TEXT PRIMARY KEY, target TEXT NOT NULL, placer TEXT NOT NULL, amount INTEGER NOT NULL, created INTEGER, expires INTEGER)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_bounty_target ON bounties(target)");
                s.execute("CREATE TABLE IF NOT EXISTS alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, type TEXT, uuid TEXT, detail TEXT)");
            }
        }
    }

    private void reconnect() throws SQLException {
        if (conn != null) { try { conn.close(); } catch (SQLException ignored) { } }
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
    }

    private void ensure() throws SQLException {
        if (conn == null || conn.isClosed()) reconnect();
    }

    /** Runs a function under the connection lock (autocommit). Reconnects once if the connection died. */
    public <T> T query(SqlFn<T> fn) throws SQLException {
        synchronized (lock) {
            long t0 = System.nanoTime();
            try {
                ensure();
                T r = fn.apply(conn);
                lastLatencyMs = (System.nanoTime() - t0) / 1_000_000;
                return r;
            } catch (SQLException e) {
                failures.incrementAndGet(); lastError = String.valueOf(e.getMessage());
                try { reconnect(); } catch (SQLException ignored) { }
                throw e;
            }
        }
    }

    /** Runs a function inside one SQL transaction; rolls back on any failure. */
    public <T> T tx(SqlFn<T> fn) throws SQLException {
        synchronized (lock) {
            long t0 = System.nanoTime();
            try {
                ensure();
                conn.setAutoCommit(false);
                try {
                    T r = fn.apply(conn);
                    conn.commit();
                    lastLatencyMs = (System.nanoTime() - t0) / 1_000_000;
                    return r;
                } catch (SQLException | RuntimeException e) {
                    try { conn.rollback(); } catch (SQLException ignored) { }
                    throw e;
                } finally {
                    try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
                }
            } catch (SQLException e) {
                failures.incrementAndGet(); lastError = String.valueOf(e.getMessage());
                throw e;
            }
        }
    }

    /** Fire-and-forget transactional write on the DB thread. Failures are logged and counted. */
    public void write(String what, SqlFn<?> fn) {
        try {
            exec.execute(() -> {
                try { tx(fn); }
                catch (Exception e) { plugin.getLogger().severe("DB write failed (" + what + "): " + e.getMessage()); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { /* shutting down */ }
    }

    public <T> CompletableFuture<T> supply(SqlFn<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try { f.complete(query(fn)); } catch (Throwable t) { f.completeExceptionally(t); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { f.completeExceptionally(e); }
        return f;
    }

    public <T> CompletableFuture<T> supplyTx(SqlFn<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try { f.complete(tx(fn)); } catch (Throwable t) { f.completeExceptionally(t); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { f.completeExceptionally(e); }
        return f;
    }

    public boolean healthy() {
        try { return query(c -> { try (Statement s = c.createStatement()) { s.execute("SELECT 1"); } return true; }); }
        catch (SQLException e) { return false; }
    }

    public long failures() { return failures.get(); }
    public long latencyMs() { return lastLatencyMs; }
    public String lastError() { return lastError; }
    public long sizeBytes() { return file.length(); }
    public File file() { return file; }

    public void close() {
        exec.shutdown();
        try { if (!exec.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("DB queue did not drain in 15s."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized (lock) { try { if (conn != null) conn.close(); } catch (SQLException ignored) { } }
    }
}
