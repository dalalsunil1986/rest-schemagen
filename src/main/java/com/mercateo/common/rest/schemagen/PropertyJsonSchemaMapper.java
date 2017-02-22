package com.mercateo.common.rest.schemagen;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mercateo.common.rest.schemagen.generator.JsonPropertyResult;
import com.mercateo.common.rest.schemagen.plugin.IndividualSchemaGenerator;

public class PropertyJsonSchemaMapper {

    private static final JsonNodeFactory nodeFactory = new JsonNodeFactory(true);

    private final PropertyJsonMapper propertyJsonMapper;

    public PropertyJsonSchemaMapper() {
        this(new PropertyJsonMapper(nodeFactory));
    }

    public PropertyJsonSchemaMapper(PropertyJsonMapper propertyJsonMapper) {
        this.propertyJsonMapper = propertyJsonMapper;
    }

    /**
     * Convert the property hierarchy given in {@code property} to a JSON
     * string.
     * 
     * @param jsonProperty
     *            property hierarchy to be converted
     * @return JSONObject representing the schema of the given property
     *         hierarchy
     */
    public ObjectNode toJson(JsonPropertyResult jsonProperty) {
        return createPropertyEntry(jsonProperty.getRoot(), createObjectNode(), jsonProperty
                .getReferencedElements());
    }

    public ObjectNode createObjectNode() {
        return new ObjectNode(nodeFactory);
    }

    private ObjectNode createPropertyEntry(JsonProperty jsonProperty,
            Set<JsonProperty> referencedElements) {
        return createPropertyEntry(jsonProperty, createObjectNode(), referencedElements);
    }

    private ObjectNode createPropertyEntry(JsonProperty jsonProperty, final ObjectNode result,
            Set<JsonProperty> referencedElements) {

        final Class<? extends IndividualSchemaGenerator> generator = jsonProperty
                .getIndividualSchemaGenerator();

        if (generator == null) {
            if (jsonProperty.getRef() != null) {
                result.put("$ref", jsonProperty.getRef());

            } else {
                if (referencedElements.contains(jsonProperty)) {
                    result.put("id", jsonProperty.getPath());
                }
                switch (jsonProperty.getType()) {
                case OBJECT:
                    result.put("type", "object");
                    result.set("properties", createProperties(jsonProperty.getProperties(),
                            referencedElements));
                    final ArrayNode requiredElements = createRequiredElementsArray(jsonProperty
                            .getProperties());
                    if (requiredElements.size() > 0) {
                        result.set("required", requiredElements);
                    }
                    break;

                case ARRAY:
                    result.put("type", "array");
                    result.set("items", createPropertyEntry(jsonProperty.getProperties().get(0),
                            referencedElements));
                    jsonProperty.getSizeConstraints().getMin().ifPresent(x -> result.put("minItems",
                            x));
                    jsonProperty.getSizeConstraints().getMax().ifPresent(x -> result.put("maxItems",
                            x));
                    break;

                case STRING:
                    result.put("type", "string");
                    propertyJsonMapper.addStringDefaultAndAllowedValues(result, jsonProperty);
                    jsonProperty.getSizeConstraints().getMin().ifPresent(x -> result.put(
                            "minLength", x));
                    jsonProperty.getSizeConstraints().getMax().ifPresent(x -> result.put(
                            "maxLength", x));
                    break;

                case INTEGER:
                    result.put("type", "integer");
                    propertyJsonMapper.addIntegerDefaultAndAllowedValues(result, jsonProperty);
                    jsonProperty.getValueConstraints().getMin().ifPresent(x -> result.put("minimum",
                            x));
                    jsonProperty.getValueConstraints().getMax().ifPresent(x -> result.put("maximum",
                            x));
                    break;

                case NUMBER:
                    result.put("type", "number");
                    propertyJsonMapper.addNumberDefaultAndAllowedValues(result, jsonProperty);
                    break;

                case BOOLEAN:
                    result.put("type", "boolean");
                    propertyJsonMapper.getBooleanDefaultAndAllowedValues(result, jsonProperty);
                    break;

                default:

                    break;
                }
            }
            return result;
        } else {
            final IndividualSchemaGenerator individualSchemaGenerator;

            try {
                individualSchemaGenerator = generator.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            return individualSchemaGenerator.create();
        }
    }

    private ObjectNode createProperties(List<JsonProperty> properties,
            Set<JsonProperty> referencedElements) {
        final ObjectNode result = createObjectNode();
        for (JsonProperty jsonProperty : properties) {
            result.set(jsonProperty.getName(), createPropertyEntry(jsonProperty,
                    referencedElements));
        }
        return result;
    }

    private ArrayNode createRequiredElementsArray(List<JsonProperty> properties) {
        final ArrayNode result = new ArrayNode(nodeFactory);
        for (JsonProperty jsonProperty : properties) {
            if (jsonProperty.isRequired()) {
                result.add(jsonProperty.getName());
            }
        }
        return result;
    }

}
