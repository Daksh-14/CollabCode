package com.collabcode.editor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CollaborativeCodeEditorBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CollaborativeCodeEditorBackendApplication.class, args);
	}

}
