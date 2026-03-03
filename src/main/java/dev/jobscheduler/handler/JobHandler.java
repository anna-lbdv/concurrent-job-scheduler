package dev.jobscheduler.handler;

public interface JobHandler {

    boolean supports(String type);

    String execute(String payload) throws Exception;
}
