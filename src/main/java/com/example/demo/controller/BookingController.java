package com.example.demo.controller;

import com.example.demo.model.Booking;
import com.example.demo.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    public String book(@RequestBody Booking b) {
        System.out.println("Nhận request /book: " + b.getName());
        service.book(b, serverId);
        return "Booking thành công!";
    }

    // ================= PHASE 1 =================
    @PostMapping("/prepare")
    public boolean prepare(@RequestBody Booking b) {
        return service.prepare(b);
    }

    // ================= PHASE 2 =================
    @PostMapping("/pre-commit")
    public String preCommit(@RequestBody Booking b) {
        return service.preCommit(b);
    }

    // ================= PHASE 3 =================
    @PostMapping("/commit")
    public String commit(@RequestBody Booking b) {
        service.commit(b);
        return "COMMIT OK";
    }

    // ================= ABORT =================
    @PostMapping("/abort")
    public String abort(@RequestBody Booking b) {
        return "ABORT OK";
    }

    // ================= LOG =================
    @GetMapping("/log")
    public List<String> logs() {
        return service.getLogs();
    }

    // ================= STATUS =================
    @GetMapping("/status")
    public Object status() {
        return service.getServerStatus();
    }
}