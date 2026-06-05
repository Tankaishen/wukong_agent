package com.wukong.agent.interfaces;

public interface IRecorder {
    public interface Listener{
        public void wakeUpListener(byte[] data,int length);
        public void asrListener(byte[] data,int length);
    }
    public void initRecorder();
}
