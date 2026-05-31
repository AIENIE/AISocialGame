package com.aisocialgame.model;

import java.util.List;

public class TurtleSoupStory {
    private final String id;
    private final String title;
    private final String prompt;
    private final String solution;
    private final String difficulty;
    private final List<String> tags;
    private final List<String> keyClues;
    private final List<String> misleadingPoints;
    private final List<TurtleSoupAnswerRule> answerRules;

    public TurtleSoupStory(String id,
                           String title,
                           String prompt,
                           String solution,
                           String difficulty,
                           List<String> tags,
                           List<String> keyClues,
                           List<String> misleadingPoints,
                           List<TurtleSoupAnswerRule> answerRules) {
        this.id = id;
        this.title = title;
        this.prompt = prompt;
        this.solution = solution;
        this.difficulty = difficulty;
        this.tags = tags;
        this.keyClues = keyClues;
        this.misleadingPoints = misleadingPoints;
        this.answerRules = answerRules;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getPrompt() { return prompt; }
    public String getSolution() { return solution; }
    public String getDifficulty() { return difficulty; }
    public List<String> getTags() { return tags; }
    public List<String> getKeyClues() { return keyClues; }
    public List<String> getMisleadingPoints() { return misleadingPoints; }
    public List<TurtleSoupAnswerRule> getAnswerRules() { return answerRules; }
}
