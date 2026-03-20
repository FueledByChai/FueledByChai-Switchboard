package com.fueledbychai.switchboard.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SwitchboardUserRepository extends JpaRepository<SwitchboardUserEntity, Long> {

    Optional<SwitchboardUserEntity> findByUsernameIgnoreCase(String username);
}
