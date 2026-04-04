package com.example.demo.service;

import com.example.demo.model.Booking;
import com.example.demo.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.Map;
import java.util.HashMap;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class BookingService {

    @Autowired
    private BookingRepository repository;

    private List<String> logs = Collections.synchronizedList(new ArrayList<>());

    private String[] otherServers = {
            "https://dien-toan-lan-hai.onrender.com",
            "https://dientoanck2.onrender.com",
            "https://demo2-75m2.onrender.com",
            "https://saythonginsomphone002.onrender.com"
    };

    private ConcurrentHashMap<String, List<Booking>> pendingCommits = new ConcurrentHashMap<>();
    private ExecutorService executor = Executors.newFixedThreadPool(5);

    private AtomicInteger clock = new AtomicInteger(0);

    // ================= CLOCK =================
    private int tick() {
        return clock.incrementAndGet();
    }

    private void updateClock(int received) {
        clock.updateAndGet(c -> Math.max(c, received) + 1);
    }

    private String log(String type, String message) {
        String time = LocalTime.now().withNano(0).toString();
        return "[" + time + "] [L=" + clock.get() + "] [" + type + "] " + message;
    }

    // ================= MAIN =================
    public void book(Booking b, String serverId) {
        tick();

        // 🔥 đảm bảo có globalId
        if (b.getGlobalId() == null) {
            b.setGlobalId(UUID.randomUUID().toString());
        }

        logs.add(log("CLIENT", "Booking: " + b.getGlobalId()));

        b.setLamportTime(clock.get());
        b.setReplicated(false);

        CompletableFuture.runAsync(() -> {
            try {
                RestTemplate rt = createRestTemplate();
                List<String> okServers = Collections.synchronizedList(new ArrayList<>());

                // ===== PREPARE =====
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (String url : otherServers) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            Boolean res = rt.postForObject(url + "/api/prepare", b, Boolean.class);
                            if (Boolean.TRUE.equals(res)) okServers.add(url);
                        } catch (Exception ignored) {}
                    }, executor));
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                int threshold = (otherServers.length / 2) + 1;

                if (okServers.size() < threshold) {
                    logs.add(log("4PC", "ABORT"));
                    return;
                }

                // ===== PRE-COMMIT =====
                List<String> preAckServers = new ArrayList<>();

                for (String url : okServers) {
                    try {
                        String res = rt.postForObject(url + "/api/pre-commit", b, String.class);
                        if ("PRE_ACK".equals(res)) preAckServers.add(url);
                    } catch (Exception ignored) {}
                }

                if (preAckServers.size() >= threshold) {

                    // ===== COMMIT LOCAL =====
                    saveOrUpdate(b);

                    // ===== COMMIT REMOTE =====
                    for (String url : preAckServers) {
                        try {
                            rt.postForObject(url + "/api/commit", b, String.class);
                        } catch (Exception e) {
                            pendingCommits
                                    .computeIfAbsent(url, k -> new ArrayList<>())
                                    .add(b);
                        }
                    }

                    // ===== SET REPLICATED =====
                    b.setReplicated(true);
                    saveOrUpdate(b);

                    // 🔥 broadcast bằng globalId
                    for (String url : preAckServers) {
                        try {
                            rt.postForObject(url + "/api/replicated/" + b.getGlobalId(), null, String.class);
                        } catch (Exception ignored) {}
                    }

                } else {
                    logs.add(log("4PC", "ABORT"));
                }

            } catch (Exception e) {
                logs.add(log("ERROR", e.getMessage()));
            }

        }, executor);
    }

    // ================= SAVE FIX =================
    private void saveOrUpdate(Booking b) {
        Booking existing = repository.findByGlobalId(b.getGlobalId());

        if (existing != null) {
            existing.setReplicated(b.isReplicated());
            existing.setLamportTime(b.getLamportTime());
            repository.save(existing);
        } else {
            repository.save(b);
        }
    }

    // ================= RECOVERY =================
    @Scheduled(fixedDelay = 5000)
    public void autoRecovery() {
        RestTemplate rt = createRestTemplate();

        for (String url : pendingCommits.keySet()) {
            List<Booking> list = pendingCommits.get(url);
            if (list == null) continue;

            Iterator<Booking> it = list.iterator();

            while (it.hasNext()) {
                Booking b = it.next();

                try {
                    rt.postForObject(url + "/api/commit", b, String.class);
                    it.remove();

                    b.setReplicated(true);
                    saveOrUpdate(b);

                    rt.postForObject(url + "/api/replicated/" + b.getGlobalId(), null, String.class);

                } catch (Exception ignored) {}
            }
        }
    }

    // ================= RECEIVE REPLICATED =================
    public void markReplicated(String globalId) {
        Booking b = repository.findByGlobalId(globalId);

        if (b != null) {
            b.setReplicated(true);
            repository.save(b);
        }
    }

    // ================= PARTICIPANT =================
    public boolean prepare(Booking b) {
        updateClock(b.getLamportTime());
        return true; // 🔥 luôn TRUE
    }

    public String preCommit(Booking b) {
        updateClock(b.getLamportTime());
        return "PRE_ACK";
    }

    public void commit(Booking b) {
        updateClock(b.getLamportTime());
        saveOrUpdate(b); // 🔥 không tạo record mới
    }

    // ================= UTIL =================
    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(3000);
        return new RestTemplate(f);
    }

    public List<String> getLogs() {
        return logs;
    }
    public Map<String, Boolean> getServerStatus() {
    Map<String, Boolean> status = new HashMap<>();

    for (String url : otherServers) {
        status.put(url, true); 

    return status;
}
    }