package com.lifeos.infrastructure.adapter.cli;

class CommandFailedException extends RuntimeException {

    CommandFailedException(String message) {
        super(message);
    }
}
