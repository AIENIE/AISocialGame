package com.aisocialgame.engine.v2;

/** The stable key describes an opportunity, not a polling request or a model attempt. */
public record TurnRequest(String actorId, String kind, String key) {}
