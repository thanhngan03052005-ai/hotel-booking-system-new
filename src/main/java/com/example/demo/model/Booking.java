package com.example.demo.model;

import jakarta.persistence.*;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 🔥 globalId dùng để đồng bộ giữa các server, dạng String
    @Column(name = "global_id", unique = true, nullable = false)
    private String globalId;

    private String name;
    private String room;

    private String checkin;
    private String checkout;

    @Column(nullable = false)
    private boolean replicated = false;

    private int lamportTime;

    // ===== Constructor =====
    public Booking() {}

    public Booking(String name, String room, String checkin, String checkout) {
        this.globalId = java.util.UUID.randomUUID().toString(); // tạo ID dạng String
        this.name = name;
        this.room = room;
        this.checkin = checkin;
        this.checkout = checkout;
        this.replicated = false;
    }

    // ===== Getter & Setter =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGlobalId() { return globalId; }
    public void setGlobalId(String globalId) { this.globalId = globalId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRoom() { return room; }
    public void setRoom(String room) { this.room = room; }

    public String getCheckin() { return checkin; }
    public void setCheckin(String checkin) { this.checkin = checkin; }

    public String getCheckout() { return checkout; }
    public void setCheckout(String checkout) { this.checkout = checkout; }

    public boolean isReplicated() { return replicated; }
    public void setReplicated(boolean replicated) { this.replicated = replicated; }

    public int getLamportTime() { return lamportTime; }
    public void setLamportTime(int lamportTime) { this.lamportTime = lamportTime; }
}