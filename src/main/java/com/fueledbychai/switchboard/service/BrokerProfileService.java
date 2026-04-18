package com.fueledbychai.switchboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fueledbychai.switchboard.api.BrokerExchangeDefinitionSnapshot;
import com.fueledbychai.switchboard.api.BrokerProfileFieldSnapshot;
import com.fueledbychai.switchboard.api.BrokerProfileSnapshot;
import com.fueledbychai.switchboard.api.BrokerProfilesResponse;
import com.fueledbychai.switchboard.api.BrokerProfileUpsertRequest;
import com.fueledbychai.switchboard.brokerprofile.BrokerEnvironment;
import com.fueledbychai.switchboard.brokerprofile.BrokerExchangeDefinition;
import com.fueledbychai.switchboard.brokerprofile.BrokerFieldDefinition;
import com.fueledbychai.switchboard.brokerprofile.BrokerProfileChangedEvent;
import com.fueledbychai.switchboard.brokerprofile.BrokerProfileEntity;
import com.fueledbychai.switchboard.brokerprofile.BrokerProfileRepository;
import com.fueledbychai.switchboard.crypto.AesGcmEncryptionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class BrokerProfileService {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final BrokerProfileRepository brokerProfileRepository;
    private final AesGcmEncryptionService encryptionService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;
    private final Map<String, BrokerExchangeDefinition> definitionsByExchange;

    public BrokerProfileService(BrokerProfileRepository brokerProfileRepository,
                                AesGcmEncryptionService encryptionService,
                                ApplicationEventPublisher applicationEventPublisher,
                                ObjectMapper objectMapper) {
        this.brokerProfileRepository = brokerProfileRepository;
        this.encryptionService = encryptionService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.objectMapper = objectMapper;
        this.definitionsByExchange = buildDefinitions();
    }

    public BrokerProfilesResponse brokerProfilesResponse() {
        List<BrokerExchangeDefinitionSnapshot> definitions = definitionsByExchange.values().stream()
                .map(this::toDefinitionSnapshot)
                .toList();
        List<BrokerProfileSnapshot> profiles = brokerProfileRepository.findAll().stream()
                .sorted((left, right) -> left.getExchangeName().compareToIgnoreCase(right.getExchangeName()))
                .map(this::toProfileSnapshot)
                .toList();
        return new BrokerProfilesResponse(definitions, profiles);
    }

    public Optional<ConfiguredBrokerProfile> configuredProfile(String exchangeName) {
        String normalized = normalizeExchange(exchangeName);
        BrokerExchangeDefinition definition = definitionsByExchange.get(normalized);
        if (definition == null) {
            return Optional.empty();
        }
        return brokerProfileRepository.findByExchangeNameIgnoreCase(normalized)
                .map(entity -> new ConfiguredBrokerProfile(
                        definition,
                        entity.getEnvironment(),
                        decryptPayload(entity.getEncryptedPayload()),
                        entity.getUpdatedAt()));
    }

    public List<BrokerExchangeDefinition> supportedDefinitions() {
        return List.copyOf(definitionsByExchange.values());
    }

    @Transactional
    public BrokerProfileSnapshot upsertProfile(String exchangeName, BrokerProfileUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Broker profile request is required.");
        }

        String normalizedExchange = normalizeExchange(exchangeName);
        BrokerExchangeDefinition definition = requireDefinition(normalizedExchange);
        BrokerEnvironment environment = BrokerEnvironment.fromInput(request.environment());
        Map<String, String> requestFields = request.fields() == null ? Map.of() : request.fields();

        BrokerProfileEntity entity = brokerProfileRepository.findByExchangeNameIgnoreCase(normalizedExchange)
                .orElseGet(BrokerProfileEntity::new);

        Map<String, String> mergedFields = entity.getEncryptedPayload() == null || entity.getEncryptedPayload().isBlank()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(decryptPayload(entity.getEncryptedPayload()));

        for (BrokerFieldDefinition field : definition.fields()) {
            boolean requestIncludesField = requestFields.containsKey(field.key());
            String incomingValue = trimToNull(requestFields.get(field.key()));

            if (field.secret()) {
                if (incomingValue != null) {
                    mergedFields.put(field.key(), incomingValue);
                } else if (!requestIncludesField && mergedFields.containsKey(field.key())) {
                    continue;
                }
            } else if (requestIncludesField) {
                if (incomingValue == null) {
                    mergedFields.remove(field.key());
                } else {
                    mergedFields.put(field.key(), incomingValue);
                }
            }

            if (field.required() && trimToNull(mergedFields.get(field.key())) == null) {
                throw new IllegalArgumentException(field.label() + " is required for " + definition.displayName() + ".");
            }
        }

        entity.setExchangeName(normalizedExchange);
        entity.setEnvironment(environment);
        entity.setEncryptedPayload(encryptPayload(mergedFields));
        BrokerProfileEntity saved = brokerProfileRepository.save(entity);
        applicationEventPublisher.publishEvent(new BrokerProfileChangedEvent(normalizedExchange));
        return toProfileSnapshot(saved);
    }

    @Transactional
    public void deleteProfile(String exchangeName) {
        String normalizedExchange = normalizeExchange(exchangeName);
        requireDefinition(normalizedExchange);
        brokerProfileRepository.deleteByExchangeNameIgnoreCase(normalizedExchange);
        applicationEventPublisher.publishEvent(new BrokerProfileChangedEvent(normalizedExchange));
    }

    private BrokerExchangeDefinition requireDefinition(String exchangeName) {
        BrokerExchangeDefinition definition = definitionsByExchange.get(exchangeName);
        if (definition == null) {
            throw new IllegalArgumentException("Unsupported broker exchange: " + exchangeName);
        }
        return definition;
    }

    private BrokerExchangeDefinitionSnapshot toDefinitionSnapshot(BrokerExchangeDefinition definition) {
        return new BrokerExchangeDefinitionSnapshot(
                definition.exchange(),
                definition.displayName(),
                definition.fields().stream()
                        .map(field -> new BrokerProfileFieldSnapshot(
                                field.key(),
                                field.label(),
                                field.secret(),
                                field.required(),
                                null,
                                false,
                                null,
                                field.helperText()))
                        .toList()
        );
    }

    private BrokerProfileSnapshot toProfileSnapshot(BrokerProfileEntity entity) {
        BrokerExchangeDefinition definition = requireDefinition(normalizeExchange(entity.getExchangeName()));
        Map<String, String> values = decryptPayload(entity.getEncryptedPayload());
        List<BrokerProfileFieldSnapshot> fields = new ArrayList<>();
        for (BrokerFieldDefinition field : definition.fields()) {
            String value = trimToNull(values.get(field.key()));
            boolean configured = value != null;
            fields.add(new BrokerProfileFieldSnapshot(
                    field.key(),
                    field.label(),
                    field.secret(),
                    field.required(),
                    field.secret() ? null : value,
                    configured,
                    field.secret() ? maskSecret(value) : value,
                    field.helperText()
            ));
        }

        String accountLabel = trimToNull(values.get(definition.accountLabelField()));
        if (accountLabel == null) {
            accountLabel = definition.displayName();
        }

        return new BrokerProfileSnapshot(
                definition.exchange(),
                definition.displayName(),
                entity.getEnvironment().name(),
                accountLabel,
                entity.getUpdatedAt(),
                fields
        );
    }

    private Map<String, BrokerExchangeDefinition> buildDefinitions() {
        LinkedHashMap<String, BrokerExchangeDefinition> definitions = new LinkedHashMap<>();
        definitions.put("HYPERLIQUID", new BrokerExchangeDefinition(
                "HYPERLIQUID",
                "Hyperliquid",
                "accountAddress",
                List.of(
                        new BrokerFieldDefinition("accountAddress", "Account Address", false, true,
                                "Wallet address used for trading."),
                        new BrokerFieldDefinition("privateKey", "Private Key", true, true,
                                "Trading private key for the wallet."),
                        new BrokerFieldDefinition("subAccountAddress", "Sub Account Address", false, false,
                                "Optional sub-account address; when set it becomes the active trading account.")
                )));
        definitions.put("PARADEX", new BrokerExchangeDefinition(
                "PARADEX",
                "Paradex",
                "accountAddress",
                List.of(
                        new BrokerFieldDefinition("accountAddress", "Account Address", false, true,
                                "Starknet account address used by Paradex."),
                        new BrokerFieldDefinition("privateKey", "Private Key", true, true,
                                "Private key used to authenticate and sign requests.")
                )));
        definitions.put("LIGHTER", new BrokerExchangeDefinition(
                "LIGHTER",
                "Lighter",
                "accountAddress",
                List.of(
                        new BrokerFieldDefinition("accountAddress", "Account Address", false, true,
                                "Trading account address for the Lighter account."),
                        new BrokerFieldDefinition("privateKey", "Private Key", true, true,
                                "Private key used to derive signed order payloads."),
                        new BrokerFieldDefinition("accountIndex", "Account Index", false, true,
                                "Numeric account index expected by the Lighter signer."),
                        new BrokerFieldDefinition("apiKeyIndex", "API Key Index", false, true,
                                "Numeric API key index expected by the Lighter signer."),
                        new BrokerFieldDefinition("signerLibraryPath", "Signer Library Path", false, false,
                                "Optional local path to the native signer library when required.")
                )));
        definitions.put("ASTER", new BrokerExchangeDefinition(
                "ASTER",
                "Aster",
                "apiKey",
                List.of(
                        new BrokerFieldDefinition("apiKey", "API Key", false, true,
                                "API key issued by the exchange."),
                        new BrokerFieldDefinition("apiSecret", "API Secret", true, true,
                                "API secret used for authenticated REST and user streams.")
                )));
        definitions.put("BYBIT", new BrokerExchangeDefinition(
                "BYBIT",
                "Bybit",
                "apiKey",
                List.of(
                        new BrokerFieldDefinition("apiKey", "API Key", false, true,
                                "Bybit API key."),
                        new BrokerFieldDefinition("apiSecret", "API Secret", true, true,
                                "Bybit API secret.")
                )));
        definitions.put("OKX", new BrokerExchangeDefinition(
                "OKX",
                "OKX",
                "accountAddress",
                List.of(
                        new BrokerFieldDefinition("accountAddress", "Account Address", false, true,
                                "Account identifier configured for OKX."),
                        new BrokerFieldDefinition("privateKey", "Private Key", true, true,
                                "Private credential configured for OKX.")
                )));
        definitions.put("DRIFT", new BrokerExchangeDefinition(
                "DRIFT",
                "Drift",
                "gatewayRestUrl",
                List.of(
                        new BrokerFieldDefinition("gatewayRestUrl", "Gateway REST URL", false, true,
                                "Running Drift gateway REST endpoint."),
                        new BrokerFieldDefinition("gatewayWsUrl", "Gateway WebSocket URL", false, true,
                                "Running Drift gateway WebSocket endpoint."),
                        new BrokerFieldDefinition("subAccountId", "Sub Account ID", false, true,
                                "Drift sub-account identifier.")
                )));
        definitions.put("HIBACHI", new BrokerExchangeDefinition(
                "HIBACHI",
                "Hibachi",
                "apiKey",
                List.of(
                        new BrokerFieldDefinition("apiKey", "API Key", false, true,
                                "Hibachi API key."),
                        new BrokerFieldDefinition("apiSecret", "API Secret", true, true,
                                "Hibachi API secret."),
                        new BrokerFieldDefinition("accountId", "Account ID", false, true,
                                "Hibachi account identifier.")
                )));
        return Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    private String encryptPayload(Map<String, String> values) {
        try {
            return encryptionService.encrypt(objectMapper.writeValueAsString(values));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize broker profile.", e);
        }
    }

    private Map<String, String> decryptPayload(String encryptedPayload) {
        try {
            String json = encryptionService.decrypt(encryptedPayload);
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load broker profile.", e);
        }
    }

    private String normalizeExchange(String exchangeName) {
        return exchangeName == null ? "" : exchangeName.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String maskSecret(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() <= 6) {
            return "*".repeat(trimmed.length());
        }
        return "*".repeat(Math.max(4, trimmed.length() - 4)) + trimmed.substring(trimmed.length() - 4);
    }

    public record ConfiguredBrokerProfile(
            BrokerExchangeDefinition definition,
            BrokerEnvironment environment,
            Map<String, String> fieldValues,
            Instant updatedAt
    ) {
        public String accountLabel() {
            return fieldValues.getOrDefault(definition.accountLabelField(), definition.displayName());
        }
    }
}
