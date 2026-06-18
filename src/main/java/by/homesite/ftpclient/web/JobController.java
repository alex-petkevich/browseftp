package by.homesite.ftpclient.web;
import by.homesite.ftpclient.service.JobService;
import by.homesite.ftpclient.service.JobService.Job;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;
    public JobController(JobService jobs) {
        this.jobs = jobs;
    }
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int max) {
        return jobs.recent(max);
    }
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        Job j = jobs.get(id);
        if (j == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(jobs.detail(j));
    }
    @DeleteMapping("/cleanup")
    public Map<String, Object> cleanup(@RequestParam(defaultValue = "600") int olderThanSeconds) {
        int removed = jobs.cleanup(olderThanSeconds);
        return Map.of("removed", removed);
    }
    /** Removes ALL completed jobs (DONE/FAILED/CANCELLED). */
    @DeleteMapping("/completed")
    public Map<String, Object> clearCompleted() {
        return Map.of("removed", jobs.clearCompleted());
    }
    /** Requests cancellation of a running/pending job. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
        boolean ok = jobs.cancel(id);
        if (!ok) {
            return ResponseEntity.status(409).body(Map.of("cancelled", false));
        }
        return ResponseEntity.ok(Map.of("cancelled", true));
    }
}
