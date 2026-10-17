package com.nocountry.simulation.communitylab.infrastructure.config.jackson;

import com.nocountry.simulation.communitylab.domain.enums.ApprovalStatus;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// Reads approved from stored records: the enum name, or the boolean written before the enum existed (spec 007 RF-01).
public class LegacyApprovalDeserializer extends ValueDeserializer<ApprovalStatus> {

    @Override
    public ApprovalStatus deserialize(JsonParser p, DeserializationContext ctxt) {
        return switch (p.currentToken()) {
            case VALUE_TRUE -> ApprovalStatus.APPROVED;
            // Why null: false alone can't tell IN_REVIEW from REJECTED; EnrichedComment decides with revised.
            case VALUE_FALSE -> null;
            case VALUE_STRING -> parse(p, ctxt);
            default -> (ApprovalStatus) ctxt.handleUnexpectedToken(ApprovalStatus.class, p);
        };
    }

    private static ApprovalStatus parse(JsonParser p, DeserializationContext ctxt) {
        String value = p.getString();
        try {
            return ApprovalStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return (ApprovalStatus) ctxt.handleWeirdStringValue(ApprovalStatus.class, value, "not an approval status");
        }
    }
}
