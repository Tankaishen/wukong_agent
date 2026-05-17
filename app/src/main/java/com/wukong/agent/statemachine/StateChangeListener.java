package com.wukong.agent.statemachine;

public interface StateChangeListener {
    void onStateChanged(BusinessState oldState, BusinessState newState);
    void onStateError(BusinessState state, String errorMessage);
}
