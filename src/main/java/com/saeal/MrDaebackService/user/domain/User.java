package com.saeal.MrDaebackService.user.domain;

import com.saeal.MrDaebackService.user.enums.Authority;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    private UUID id;
    private String username;
    private String password;
    private String email;
    private String displayName;
    private Authority authority;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
