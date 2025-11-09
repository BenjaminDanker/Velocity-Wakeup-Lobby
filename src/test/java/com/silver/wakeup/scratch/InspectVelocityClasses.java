package com.silver.wakeup.scratch;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import java.lang.reflect.Method;

public class InspectVelocityClasses {
    public static void main(String[] args) {
        Class<?> clazz = CommandExecuteEvent.CommandResult.class;
        System.out.println("Methods on CommandResult:");
        for (Method m : clazz.getDeclaredMethods()) {
            System.out.println(" - " + m);
        }
    }
}
