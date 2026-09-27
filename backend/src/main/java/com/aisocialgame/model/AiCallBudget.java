package com.aisocialgame.model;

import jakarta.persistence.*;

@Entity @Table(name = "ai_call_budgets")
public class AiCallBudget {
    @Id @Column(length = 96) private String id;
    @Column(nullable = false) private int consumed;
    public AiCallBudget() {}
    public AiCallBudget(String id) { this.id = id; }
    public String getId() { return id; }
    public int getConsumed() { return consumed; }
    public void setConsumed(int consumed) { this.consumed = consumed; }
}
