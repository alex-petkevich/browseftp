package by.homesite.ftpclient.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/**
 * Tracks long-running FTP operations as asynchronous "jobs" so the UI can
 * display progress and the protocol log while they run. Jobs are kept in memory
 * (a process restart clears them) and the most recent ones are returned by
 * {@link #recent(int)}.
 */
@Service
public class JobService {

    public enum Status { PENDING, RUNNING, DONE, FAILED, CANCELLED }

    /** A single FTP job. Methods are thread-safe enough for our use: the
     *  background thread mutates fields while polling reads them; lists are
     *  synchronized. */
    public static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final String kind;          // upload, download, delete, copy-remote, ftp-delete, ...
        private final String description;
        private final Instant createdAt = Instant.now();
        private Instant startedAt;
        private Instant finishedAt;
        private volatile Status status = Status.PENDING;
        private volatile long total = 0;
        private volatile long processed = 0;
        private volatile String currentItem = "";
        private volatile String error;
        private final List<String> log = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean cancelRequested = false;
        /** Optional callback invoked when the user requests cancellation
         *  (used by the FTP code to disconnect the in-flight client). */
        private volatile Runnable cancelAction;

        public Job(String kind, String description) {
            this.kind = kind;
            this.description = description;
        }

        public String getId() { return id; }
        public String getKind() { return kind; }
        public String getDescription() { return description; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getFinishedAt() { return finishedAt; }
        public Status getStatus() { return status; }
        public long getTotal() { return total; }
        public long getProcessed() { return processed; }
        public String getCurrentItem() { return currentItem; }
        public String getError() { return error; }
        public List<String> getLog() {
            synchronized (log) {
                return new ArrayList<>(log);
            }
        }

        // mutators used by the running task
        public void setStatus(Status s) { this.status = s; }
        public void setStartedAt(Instant t) { this.startedAt = t; }
        public void setFinishedAt(Instant t) { this.finishedAt = t; }
        public void setTotal(long t) { this.total = t; }
        public void setProcessed(long p) { this.processed = p; }
        public synchronized void incProcessed() { this.processed++; }
        /** Atomically adds {@code n} to the processed counter. */
        public synchronized void addProcessed(long n) { this.processed += n; }
        public void setCurrentItem(String s) { this.currentItem = s; }
        public void setError(String e) { this.error = e; }
        public void addLog(String line) { log.add(line); }
        /** Live, mutable view for FTP listeners to append into. */
        public List<String> logRef() { return log; }

        public boolean isCancelled() { return cancelRequested; }
        public void setCancelAction(Runnable r) { this.cancelAction = r; }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /** Creates and registers a job. Caller is responsible for submitting work. */
    public Job createJob(String kind, String description) {
        Job j = new Job(kind, description);
        jobs.put(j.getId(), j);
        return j;
    }

    /** Submits the work for an existing job to the executor. */
    public void run(Job job, Runnable task) {
        executor.submit(() -> {
            job.setStartedAt(Instant.now());
            job.setStatus(Status.RUNNING);
            try {
                task.run();
                if (job.isCancelled()) {
                    job.setStatus(Status.CANCELLED);
                    job.addLog("Cancelled.");
                } else {
                    job.setStatus(Status.DONE);
                    job.addLog("Completed.");
                }
            } catch (Throwable t) {
                if (job.isCancelled()) {
                    job.setStatus(Status.CANCELLED);
                    job.addLog("Cancelled.");
                } else {
                    job.setStatus(Status.FAILED);
                    job.setError(t.getMessage() == null ? t.toString() : t.getMessage());
                    job.addLog("ERROR: " + job.getError());
                }
            } finally {
                job.setFinishedAt(Instant.now());
            }
        });
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    /** Returns the most-recent jobs (newest first), limited to {@code max}. */
    public List<Map<String, Object>> recent(int max) {
        return jobs.values().stream()
                .sorted(Comparator.comparing(Job::getCreatedAt).reversed())
                .limit(max)
                .map(this::summary)
                .toList();
    }

    /** Returns the full snapshot of one job, including the log. */
    public Map<String, Object> detail(Job j) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(summary(j));
        m.put("log", j.getLog());
        return m;
    }

    private Map<String, Object> summary(Job j) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("kind", j.getKind());
        m.put("description", j.getDescription());
        m.put("status", j.getStatus().name());
        m.put("cancelRequested", j.isCancelled());
        m.put("total", j.getTotal());
        m.put("processed", j.getProcessed());
        m.put("currentItem", j.getCurrentItem());
        m.put("error", j.getError());
        m.put("createdAt", j.getCreatedAt().toString());
        m.put("startedAt", j.getStartedAt() == null ? null : j.getStartedAt().toString());
        m.put("finishedAt", j.getFinishedAt() == null ? null : j.getFinishedAt().toString());
        return m;
    }

    /** Requests cancellation of a pending/running job and triggers its cancel action. */
    public boolean cancel(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            return false;
        }
        if (j.status != Status.PENDING && j.status != Status.RUNNING) {
            return false;
        }
        if (j.cancelRequested) {
            // Already requested — ignore repeat clicks, no extra log spam.
            return true;
        }
        j.cancelRequested = true;
        j.addLog("Cancel requested.");
        Runnable r = j.cancelAction;
        if (r != null) {
            try { r.run(); } catch (Throwable ignored) { /* best-effort */ }
        }
        return true;
    }

    /** Removes ALL completed jobs (DONE/FAILED/CANCELLED). Returns count removed. */
    public int clearCompleted() {
        int removed = 0;
        for (var e : jobs.entrySet()) {
            Status s = e.getValue().getStatus();
            if (s == Status.DONE || s == Status.FAILED || s == Status.CANCELLED) {
                jobs.remove(e.getKey());
                removed++;
            }
        }
        return removed;
    }

    /** Removes finished jobs older than the given age (seconds). Returns count removed. */
    public int cleanup(int olderThanSeconds) {
        Instant cutoff = Instant.now().minusSeconds(olderThanSeconds);
        int removed = 0;
        for (var e : jobs.entrySet()) {
            Job j = e.getValue();
            if (j.getFinishedAt() != null && j.getFinishedAt().isBefore(cutoff)) {
                jobs.remove(e.getKey());
                removed++;
            }
        }
        return removed;
    }
}
