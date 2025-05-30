package com.collabcode.editor.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name="sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionEntity {

    @Id
    private String id;

    private String sessionName;

    private Instant createdAt;
}
