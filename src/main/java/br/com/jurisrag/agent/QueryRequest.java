package br.com.jurisrag.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QueryRequest(@NotBlank @Size(max = 2000) String question) { }
