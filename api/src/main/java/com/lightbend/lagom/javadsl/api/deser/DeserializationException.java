/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package com.lightbend.lagom.javadsl.api.deser;

import com.lightbend.lagom.javadsl.api.transport.TransportErrorCode;
import com.lightbend.lagom.javadsl.api.transport.TransportException;

/**
 * Exception thrown when there is a problem deserializing a message.
 */
public class DeserializationException extends TransportException {
    public static final TransportErrorCode ERROR_CODE = TransportErrorCode.UnsupportedData;

    public DeserializationException(String message) {
        super(ERROR_CODE, message);
    }

    public DeserializationException(Throwable cause) {
        super(ERROR_CODE, cause);
    }

    public DeserializationException(TransportErrorCode errorCode, ExceptionMessage exceptionMessage) {
        super(errorCode, exceptionMessage);
    }
}
