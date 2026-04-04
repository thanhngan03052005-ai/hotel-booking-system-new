package com.example.demo.controller;

import com.example.demo.model.Booking;
import com.example.demo.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class BookingController {

    @Autowired
    private BookingService service;

    @Value("${server.id}")
    private String serverId;

    // ================= CLIENT =================
    @PostMapping("/book")
    public ResponseEntity<String> book(@RequestBody Booking b) {
        if (b == null || b.getName() == null || b.getRoom() == null) {
            return ResponseEntity.badRequest().body("Dữ liệu booking không hợp lệ");
        }

        System.out.println("Nhận request /book: " + b.getName());
        service.book(b, serverId);

        return ResponseEntity.ok("Booking thành công!");
    }

    // ================= PHASE 1 =================
    @PostMapping("/prepare")
    public ResponseEntity<Boolean> prepare(@RequestBody Booking b) {
        boolean result = service.prepare(b); // luôn TRUE (đã fix ở service)
        return ResponseEntity.ok(result);
    }

    // ================= PHASE 2 =================
    @PostMapping("/pre-commit")
    public ResponseEntity<String> preCommit(@RequestBody Booking b) {
        String result = service.preCommit(b);
        return ResponseEntity.ok(result);
    }

    // ================= PHASE 3 =================
    @PostMapping("/commit")
    public ResponseEntity<String> commit(@RequestBody Booking b) {
        try {
            service.commit(b);
            return ResponseEntity.ok("COMMIT OK");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("COMMIT FAILED: " + e.getMessage());
        }
    }

    // ================= MARK REPLICATED =================
    @PostMapping("/replicated/{globalId}")
    public ResponseEntity<String> markReplicated(@PathVariable String globalId) {
        try {
            service.markReplicated(globalId);
            return ResponseEntity.ok("REPLICATED OK");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("FAILED: " + e.getMessage());
        }
    }

    // ================= ABORT =================
    @PostMapping("/abort/{globalId}")
public ResponseEntity<String> abort(@PathVariable String globalId) {
    try {
        service.abort(globalId); 
        return ResponseEntity.ok("ABORT OK");
    } catch (Exception e) {
        e.printStackTrace();
        return ResponseEntity.status(500).body("ABORT FAILED: " + e.getMessage());
    }
}

    // ================= LOG =================
    @GetMapping("/log")
    public ResponseEntity<List<String>> logs() {
        return ResponseEntity.ok(service.getLogs());
    }

    // ================= STATUS =================
    @GetMapping("/status")
    public ResponseEntity<Object> status() {
        return ResponseEntity.ok(service.getServerStatus());
    }
}