package com.example.demo.service;

import com.example.demo.model.Booking;
import com.example.demo.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;

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
            "https://saythonginsomphone002.onrender.com",
            "https://demo3-ieuy.onrender.com"
    };

    private ConcurrentHashMap<String, Boolean> serverStatus = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, List<Booking>> pendingCommits = new ConcurrentHashMap<>();

    private ExecutorService executor = Executors.newFixedThreadPool(5);

    private AtomicInteger clock = new AtomicInteger(0);

    public BookingService() {
        for (String url : otherServers) {
            serverStatus.put(url, true);
        }
    }

    // ================= LAMPORT =================
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
        logs.add(log("CLIENT", "Nhận request: " + b.getName()));
        b.setLamportTime(clock.get());
        b.setReplicated(false); // mặc định

        CompletableFuture.runAsync(() -> {

            try {
                RestTemplate rt = createRestTemplate();
                List<String> okServers = Collections.synchronizedList(new ArrayList<>());

                // ===== PHASE 1: PREPARE =====
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (String url : otherServers) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            tick();
                            logs.add(log("4PC", "PREPARE → " + url));

                            Boolean res = rt.postForObject(url + "/api/prepare", b, Boolean.class);

                            if (Boolean.TRUE.equals(res)) {
                                okServers.add(url);
logs.add(log("4PC", "VOTE OK từ " + url));
                            }

                        } catch (Exception e) {
                            serverStatus.put(url, false);
                            logs.add(log("ERROR", "PREPARE fail: " + url));
                        }
                    }, executor));
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                int threshold = (otherServers.length / 2) + 1;

                if (okServers.size() < threshold) {
                    logs.add(log("4PC", "ABORT: Không đủ quorum"));
                    sendAbort(rt, okServers, b);
                    return;
                }

                logs.add(log("4PC", "QUORUM OK → PRE-COMMIT"));

                // ===== PHASE 2: PRE-COMMIT =====
                List<String> preAckServers = Collections.synchronizedList(new ArrayList<>());
                List<CompletableFuture<Void>> preFutures = new ArrayList<>();

                for (String url : okServers) {
                    preFutures.add(CompletableFuture.runAsync(() -> {
                        try {
                            tick();
                            logs.add(log("4PC", "PRE-COMMIT → " + url));

                            String res = rt.postForObject(url + "/api/pre-commit", b, String.class);

                            if ("PRE_ACK".equals(res)) {
                                preAckServers.add(url);
                                logs.add(log("4PC", "PRE_ACK từ " + url));
                            }

                        } catch (Exception e) {
                            logs.add(log("ERROR", "PRE-COMMIT fail: " + url));
                        }
                    }, executor));
                }

                CompletableFuture.allOf(preFutures.toArray(new CompletableFuture[0])).join();

                // ===== DECISION =====
                if (preAckServers.size() >= threshold) {

                    tick();
                    logs.add(log("4PC", "ĐỦ PRE_ACK → COMMIT"));

                    // ===== COMMIT LOCAL =====
                    repository.save(b);
                    logs.add(log("DATABASE", "COMMIT local OK"));

                    int success = 0;

                    // ===== COMMIT REMOTE =====
                    for (String url : preAckServers) {
                        try {
                            rt.postForObject(url + "/api/commit", b, String.class);
                            success++;
                            logs.add(log("4PC", "COMMIT → " + url));
                        } catch (Exception e) {
                            logs.add(log("ERROR", "COMMIT fail: " + url));

                            pendingCommits
                                    .computeIfAbsent(url, k -> Collections.synchronizedList(new ArrayList<>()))
                                    .add(b);
                        }
                    }
// ===== SET REPLICATED =====
                    if (success == preAckServers.size()) {
                        b.setReplicated(true);
                        repository.save(b);
                        logs.add(log("DATABASE", "REPLICATED = TRUE"));
                    }

                } else {
                    logs.add(log("4PC", "TIMEOUT → ABORT"));
                    sendAbort(rt, okServers, b);
                }

            } catch (Exception e) {
                logs.add(log("FATAL", "THREAD CRASH: " + e.getMessage()));
            }

        }, executor);
    }

    // ================= RECOVERY =================
    @Scheduled(fixedDelay = 5000)
    public void autoRecovery() {
        RestTemplate rt = createRestTemplate();

        for (String url : pendingCommits.keySet()) {

            List<Booking> list = pendingCommits.get(url);
            if (list == null || list.isEmpty()) continue;

            Iterator<Booking> it = list.iterator();

            while (it.hasNext()) {
                Booking b = it.next();

                try {
                    rt.postForObject(url + "/api/commit", b, String.class);
                    logs.add(log("RECOVERY", "Đồng bộ lại thành công → " + url));
                    it.remove();

                    // kiểm tra booking này còn pending ở server nào không
                    boolean done = true;
                    for (List<Booking> otherList : pendingCommits.values()) {
                        if (otherList.contains(b)) {
                            done = false;
                            break;
                        }
                    }

                    // nếu sync xong hết → set TRUE
                    if (done) {
                        b.setReplicated(true);
                        repository.save(b);
                        logs.add(log("DATABASE", "RECOVERY → REPLICATED = TRUE"));
                    }

                } catch (Exception e) {
                    logs.add(log("RECOVERY", "Server chưa sống → " + url));
                }
            }
        }
    }

    // ================= ABORT =================
    private void sendAbort(RestTemplate rt, List<String> servers, Booking b) {
        for (String url : servers) {
            try {
                rt.postForObject(url + "/api/abort", b, String.class);
            } catch (Exception ignored) {}
        }
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(3000);
        return new RestTemplate(f);
    }

    // ================= PARTICIPANT =================
    public boolean prepare(Booking b) {
        updateClock(b.getLamportTime());
        logs.add(log("4PC", "Nhận PREPARE"));
        return true;
    }

    public String preCommit(Booking b) {
        updateClock(b.getLamportTime());
logs.add(log("4PC", "Nhận PRE-COMMIT"));
        return "PRE_ACK";
    }

    public void commit(Booking b) {
        updateClock(b.getLamportTime());
        repository.save(b);
        logs.add(log("4PC", "COMMIT thành công"));
    }

    // ================= GET =================
    public List<String> getLogs() { return logs; }
    public ConcurrentHashMap<String, Boolean> getServerStatus() { return serverStatus; }
}