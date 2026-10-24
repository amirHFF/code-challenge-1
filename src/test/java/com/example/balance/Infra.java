package com.example.balance;

/**
 * Provides connection details for integration tests.
 * Uses locally installed PostgreSQL and an external Redis instance.
 */
public final class Infra {

    private Infra() {
    }

    static void start() {
        // Nothing to start – relying on already running local/external services.
        // You can add a simple connectivity check here if you want.
    }

    static String jdbcUrl() {
        // Change these values according to your local PostgreSQL setup
        return "jdbc:postgresql://localhost:5432/postgres";
    }

    static String dbUser() {
        return "postgres";          // یا یوزر خودتان
    }

    static String dbPassword() {
        return "amir_123";     // پسورد محلی خودتان
    }

    static String redisHost() {
        return "185.204.170.204";
    }
    static String redisPass() {
        return "_project-z_";
    }

    static int redisPort() {
        return 6379;
    }
}