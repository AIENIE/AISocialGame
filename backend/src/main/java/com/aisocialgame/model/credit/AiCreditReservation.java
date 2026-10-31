package com.aisocialgame.model.credit;
import jakarta.persistence.*;

@Entity
@Table(name="ai_credit_reservations", uniqueConstraints=@UniqueConstraint(name="uk_ai_credit_identity",columnNames="identity_hash"),
 indexes=@Index(name="idx_ai_credit_reconcile",columnList="state,updated_at"))
public class AiCreditReservation {
    @Id @Column(length=36) public String id;
    @Column(name="identity_hash",nullable=false,length=64) public String identityHash;
    @Column(name="request_hash",nullable=false,length=64) public String requestHash;
    @Column(name="request_id",nullable=false,length=128) public String requestId;
    @Column(name="project_key",nullable=false,length=64) public String projectKey;
    @Column(name="user_id",nullable=false) public long userId;
    @Column(name="budget_id",nullable=false,length=36) public String budgetId;
    @Column(nullable=false,length=32) public String state;
    @Column(name="updated_at",nullable=false) public long updatedAt;
    @Lob @Column(name="result_base64",columnDefinition="LONGTEXT") public String resultBase64;
    @Lob @Column(name="billing_quote_base64",columnDefinition="LONGTEXT") public String billingQuoteBase64;
    @Column(length=36) public String labExperimentId;
    @Column(length=36) public String labMatchId;
    @Column(length=24) public String labPurpose;
    public Long billedPoints;
    @Column(precision=24,scale=6) public java.math.BigDecimal billedCost;
}
