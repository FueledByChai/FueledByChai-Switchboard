package com.fueledbychai.switchboard.brokerprofile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrokerProfileRepository extends JpaRepository<BrokerProfileEntity, Long> {

    Optional<BrokerProfileEntity> findByExchangeNameIgnoreCase(String exchangeName);

    void deleteByExchangeNameIgnoreCase(String exchangeName);
}
