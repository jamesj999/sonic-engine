package com.openggf.tools;

final class CliArguments {

    private CliArguments() {
    }

    static String requireValue(String[] argv, int index, String flag) {
        if (index >= argv.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return argv[index];
    }

    static int parseInt(String raw) {
        return Integer.parseInt(raw);
    }
}
