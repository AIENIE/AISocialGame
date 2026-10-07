package com.aisocialgame.dto;

import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.model.User;

public class AuthUserView {
    private String id;
    private Long externalUserId;
    private String username;
    private String nickname;
    private String email;
    private String avatar;
    private int level;
    private long coins;
    private BalanceView balance;
    private boolean balanceAvailable;

    public AuthUserView(User user, BalanceSnapshot balanceSnapshot) {
        this.id = user.getId();
        this.externalUserId = user.getExternalUserId();
        this.username = user.getUsername();
        this.nickname = user.getNickname();
        this.email = user.getEmail();
        this.avatar = user.getAvatar();
        this.level = user.getLevel();
        this.balanceAvailable = balanceSnapshot != null;
        this.coins = balanceAvailable ? balanceSnapshot.projectTempTokens() + balanceSnapshot.projectPermanentTokens() : 0;
        this.balance = balanceAvailable ? new BalanceView(balanceSnapshot) : null;
    }

    public String getId() {
        return id;
    }

    public Long getExternalUserId() {
        return externalUserId;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname;
    }

    public String getEmail() {
        return email;
    }

    public String getAvatar() {
        return avatar;
    }

    public int getLevel() {
        return level;
    }

    public long getCoins() {
        return coins;
    }

    public boolean isBalanceAvailable() { return balanceAvailable; }

    public BalanceView getBalance() {
        return balance;
    }
}
