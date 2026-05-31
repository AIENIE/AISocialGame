package com.aisocialgame.model;

import java.util.List;

public class TurtleSoupAnswerRule {
    private final List<String> keywords;
    private final String answerType;
    private final String clue;

    public TurtleSoupAnswerRule(List<String> keywords, String answerType, String clue) {
        this.keywords = keywords;
        this.answerType = answerType;
        this.clue = clue;
    }

    public List<String> getKeywords() { return keywords; }
    public String getAnswerType() { return answerType; }
    public String getClue() { return clue; }
}
