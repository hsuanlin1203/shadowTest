package com.example.shadowtest.api;

import jakarta.validation.constraints.NotBlank;

public record TriggerRequest(@NotBlank String id) {}
