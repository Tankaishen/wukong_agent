package com.wukong.agent.customException;

public class CustomException extends Exception {
    public CustomException(String message){
        //出现异常打印的语句
        super(message);
    }
}
