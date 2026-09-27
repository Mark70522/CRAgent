package com.company.cragent.servicenow;

public class ServiceNowException extends RuntimeException {
    public ServiceNowException(String message) { super(message); }
    public ServiceNowException(String message, Throwable cause) { super(message, cause); }
}
