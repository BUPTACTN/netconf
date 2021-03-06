/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.netconf.sal.rest.doc.impl;

import static org.opendaylight.netconf.sal.rest.doc.util.RestDocgenUtil.resolveNodesName;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.concurrent.NotThreadSafe;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opendaylight.netconf.sal.rest.doc.model.builder.OperationBuilder;
import org.opendaylight.netconf.sal.rest.doc.model.builder.OperationBuilder.Post;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.model.api.AnyXmlSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ChoiceCaseNode;
import org.opendaylight.yangtools.yang.model.api.ChoiceSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ConstraintDefinition;
import org.opendaylight.yangtools.yang.model.api.ContainerSchemaNode;
import org.opendaylight.yangtools.yang.model.api.DataNodeContainer;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.IdentitySchemaNode;
import org.opendaylight.yangtools.yang.model.api.LeafListSchemaNode;
import org.opendaylight.yangtools.yang.model.api.LeafSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ListSchemaNode;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.RpcDefinition;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaNode;
import org.opendaylight.yangtools.yang.model.api.TypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.BinaryTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.BitsTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.BitsTypeDefinition.Bit;
import org.opendaylight.yangtools.yang.model.api.type.BooleanTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.DecimalTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.EnumTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.EnumTypeDefinition.EnumPair;
import org.opendaylight.yangtools.yang.model.api.type.IdentityrefTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.IntegerTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.LengthConstraint;
import org.opendaylight.yangtools.yang.model.api.type.StringTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.UnionTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.UnsignedIntegerTypeDefinition;
import org.opendaylight.yangtools.yang.model.util.ExtendedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates JSON Schema for data defined in YANG.
 */
@NotThreadSafe
public class ModelGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(ModelGenerator.class);

    private static final String BASE_64 = "base64";
    private static final String BINARY_ENCODING_KEY = "binaryEncoding";
    private static final String MEDIA_KEY = "media";
    private static final String ONE_OF_KEY = "oneOf";
    private static final String UNIQUE_ITEMS_KEY = "uniqueItems";
    private static final String MAX_ITEMS = "maxItems";
    private static final String MIN_ITEMS = "minItems";
    private static final String SCHEMA_URL = "http://json-schema.org/draft-04/schema";
    private static final String SCHEMA_KEY = "$schema";
    private static final String MAX_LENGTH_KEY = "maxLength";
    private static final String MIN_LENGTH_KEY = "minLength";
    private static final String REQUIRED_KEY = "required";
    private static final String REF_KEY = "$ref";
    private static final String ITEMS_KEY = "items";
    private static final String TYPE_KEY = "type";
    private static final String PROPERTIES_KEY = "properties";
    private static final String DESCRIPTION_KEY = "description";
    private static final String OBJECT_TYPE = "object";
    private static final String ARRAY_TYPE = "array";
    private static final String ENUM = "enum";
    private static final String INTEGER = "integer";
    private static final String NUMBER = "number";
    private static final String BOOLEAN = "boolean";
    private static final String STRING = "string";
    private static final String ID_KEY = "id";
    private static final String SUB_TYPES_KEY = "subTypes";

    private Module topLevelModule;

    public ModelGenerator() {
    }

    private static String jsonTypeFor(final TypeDefinition<?> type) {
        if (type instanceof BooleanTypeDefinition) {
            return BOOLEAN;
        } else if (type instanceof DecimalTypeDefinition) {
            return NUMBER;
        } else if (type instanceof EnumTypeDefinition) {
            return ENUM;
        } else if (type instanceof IntegerTypeDefinition) {
            return INTEGER;
        } else if (type instanceof UnsignedIntegerTypeDefinition) {
            return INTEGER;
        } else if (type instanceof StringTypeDefinition) {
            return STRING;
        }

        // TODO: Binary type
        return null;
    }

    public JSONObject convertToJsonSchema(final Module module, final SchemaContext schemaContext) throws IOException, JSONException {
        JSONObject models = new JSONObject();
        topLevelModule = module;
        processModules(module, models);
        processContainersAndLists(module, models, schemaContext);
        processRPCs(module, models, schemaContext);
        processIdentities(module, models);
        return models;
    }

    private void processModules(final Module module, final JSONObject models) throws JSONException {
        createConcreteModelForPost(models, module.getName() + BaseYangSwaggerGenerator.MODULE_NAME_SUFFIX, createPropertiesForPost(module));
    }

    private void processContainersAndLists(final Module module, final JSONObject models, final SchemaContext schemaContext)
            throws IOException, JSONException {

        String moduleName = module.getName();

        for (DataSchemaNode childNode : module.getChildNodes()) {
            // For every container and list in the module
            if (childNode instanceof ContainerSchemaNode || childNode instanceof ListSchemaNode) {
                processDataNodeContainer((DataNodeContainer) childNode, moduleName, models, true, schemaContext);
                processDataNodeContainer((DataNodeContainer) childNode, moduleName, models, false, schemaContext);
            }
        }

    }

    /**
     * Process the RPCs for a Module Spits out a file each of the name <rpcName>-input.json and <rpcName>-output.json
     * for each RPC that contains input & output elements
     *
     * @param module
     * @throws JSONException
     * @throws IOException
     */
    private void processRPCs(final Module module, final JSONObject models, final SchemaContext schemaContext) throws JSONException,
            IOException {

        Set<RpcDefinition> rpcs = module.getRpcs();
        String moduleName = module.getName();
        for (RpcDefinition rpc : rpcs) {

            ContainerSchemaNode input = rpc.getInput();
            if (input != null) {
                JSONObject inputJSON = processDataNodeContainer(input, moduleName, models, schemaContext);
                String filename = "(" + rpc.getQName().getLocalName() + ")input";
                inputJSON.put("id", filename);
                // writeToFile(filename, inputJSON.toString(2), moduleName);
                models.put(filename, inputJSON);
            }

            ContainerSchemaNode output = rpc.getOutput();
            if (output != null) {
                JSONObject outputJSON = processDataNodeContainer(output, moduleName, models, schemaContext);
                String filename = "(" + rpc.getQName().getLocalName() + ")output";
                outputJSON.put("id", filename);
                models.put(filename, outputJSON);
            }
        }
    }

    /**
     * Processes the 'identity' statement in a yang model and maps it to a 'model' in the Swagger JSON spec.
     *
     * @param module
     *            The module from which the identity stmt will be processed
     * @param models
     *            The JSONObject in which the parsed identity will be put as a 'model' obj
     */
    private static void processIdentities(final Module module, final JSONObject models) throws JSONException {

        String moduleName = module.getName();
        Set<IdentitySchemaNode> idNodes = module.getIdentities();
        LOG.debug("Processing Identities for module {} . Found {} identity statements", moduleName, idNodes.size());

        for (IdentitySchemaNode idNode : idNodes) {
            JSONObject identityObj = new JSONObject();
            String identityName = idNode.getQName().getLocalName();
            LOG.debug("Processing Identity: {}", identityName);

            identityObj.put(ID_KEY, identityName);
            identityObj.put(DESCRIPTION_KEY, idNode.getDescription());

            JSONObject props = new JSONObject();
            IdentitySchemaNode baseId = idNode.getBaseIdentity();

            if (baseId == null) {
                /**
                 * This is a base identity. So lets see if it has sub types. If it does, then add them to the model
                 * definition.
                 */
                Set<IdentitySchemaNode> derivedIds = idNode.getDerivedIdentities();

                if (derivedIds != null) {
                    JSONArray subTypes = new JSONArray();
                    for (IdentitySchemaNode derivedId : derivedIds) {
                        subTypes.put(derivedId.getQName().getLocalName());
                    }
                    identityObj.put(SUB_TYPES_KEY, subTypes);
                }
            } else {
                /**
                 * This is a derived entity. Add it's base type & move on.
                 */
                props.put(TYPE_KEY, baseId.getQName().getLocalName());
            }

            // Add the properties. For a base type, this will be an empty object as required by the Swagger spec.
            identityObj.put(PROPERTIES_KEY, props);
            models.put(identityName, identityObj);
        }
    }

    /**
     * Processes the container and list nodes and populates the moduleJSON.
     */
    private JSONObject processDataNodeContainer(final DataNodeContainer dataNode, final String moduleName, final JSONObject models,
            final SchemaContext schemaContext) throws JSONException, IOException {
        return processDataNodeContainer(dataNode, moduleName, models, true, schemaContext);
    }

    private JSONObject processDataNodeContainer(final DataNodeContainer dataNode, final String moduleName, final JSONObject models,
            final boolean isConfig, final SchemaContext schemaContext) throws JSONException, IOException {
        if (dataNode instanceof ListSchemaNode || dataNode instanceof ContainerSchemaNode) {
            Preconditions.checkArgument(dataNode instanceof SchemaNode, "Data node should be also schema node");
            Iterable<DataSchemaNode> containerChildren = dataNode.getChildNodes();
            JSONObject properties = processChildren(containerChildren, ((SchemaNode) dataNode).getQName(), moduleName,
                    models, isConfig, schemaContext);

            String nodeName = (isConfig ? OperationBuilder.CONFIG : OperationBuilder.OPERATIONAL)
                    + ((SchemaNode) dataNode).getQName().getLocalName();

            JSONObject childSchema = getSchemaTemplate();
            childSchema.put(TYPE_KEY, OBJECT_TYPE);
            childSchema.put(PROPERTIES_KEY, properties);
            childSchema.put("id", nodeName);
            models.put(nodeName, childSchema);

            if (isConfig) {
                createConcreteModelForPost(models, ((SchemaNode) dataNode).getQName().getLocalName(),
                        createPropertiesForPost(dataNode));
            }

            JSONObject items = new JSONObject();
            items.put(REF_KEY, nodeName);
            JSONObject dataNodeProperties = new JSONObject();
            dataNodeProperties.put(TYPE_KEY, dataNode instanceof ListSchemaNode ? ARRAY_TYPE : OBJECT_TYPE);
            dataNodeProperties.put(ITEMS_KEY, items);

            return dataNodeProperties;
        }
        return null;
    }

    private static void createConcreteModelForPost(final JSONObject models, final String localName,
            final JSONObject properties) throws JSONException {
        String nodePostName = OperationBuilder.CONFIG + localName + Post.METHOD_NAME;
        JSONObject postSchema = getSchemaTemplate();
        postSchema.put(TYPE_KEY, OBJECT_TYPE);
        postSchema.put("id", nodePostName);
        postSchema.put(PROPERTIES_KEY, properties);
        models.put(nodePostName, postSchema);
    }

    private JSONObject createPropertiesForPost(final DataNodeContainer dataNodeContainer) throws JSONException {
        JSONObject properties = new JSONObject();
        for (DataSchemaNode childNode : dataNodeContainer.getChildNodes()) {
            if (childNode instanceof ListSchemaNode || childNode instanceof ContainerSchemaNode) {
                JSONObject items = new JSONObject();
                items.put(REF_KEY, "(config)" + childNode.getQName().getLocalName());
                JSONObject property = new JSONObject();
                property.put(TYPE_KEY, childNode instanceof ListSchemaNode ? ARRAY_TYPE : OBJECT_TYPE);
                property.put(ITEMS_KEY, items);
                properties.put(childNode.getQName().getLocalName(), property);
            } else if (childNode instanceof LeafSchemaNode) {
                JSONObject property = processLeafNode((LeafSchemaNode)childNode);
                properties.put(childNode.getQName().getLocalName(), property);
            }
        }
        return properties;
    }

    private JSONObject processChildren(final Iterable<DataSchemaNode> nodes, final QName parentQName, final String moduleName,
            final JSONObject models, final SchemaContext schemaContext) throws JSONException, IOException {
        return processChildren(nodes, parentQName, moduleName, models, true, schemaContext);
    }

    /**
     * Processes the nodes.
     */
    private JSONObject processChildren(final Iterable<DataSchemaNode> nodes, final QName parentQName,
            final String moduleName, final JSONObject models, final boolean isConfig, final SchemaContext schemaContext)
            throws JSONException, IOException {

        JSONObject properties = new JSONObject();

        for (DataSchemaNode node : nodes) {
            if (node.isConfiguration() == isConfig) {

                String name = resolveNodesName(node, topLevelModule, schemaContext);
                JSONObject property = null;
                if (node instanceof LeafSchemaNode) {
                    property = processLeafNode((LeafSchemaNode) node);
                } else if (node instanceof ListSchemaNode) {
                    property = processDataNodeContainer((ListSchemaNode) node, moduleName, models, isConfig,
                            schemaContext);

                } else if (node instanceof LeafListSchemaNode) {
                    property = processLeafListNode((LeafListSchemaNode) node);

                } else if (node instanceof ChoiceSchemaNode) {
                    property = processChoiceNode((ChoiceSchemaNode) node, moduleName, models, schemaContext);

                } else if (node instanceof AnyXmlSchemaNode) {
                    property = processAnyXMLNode((AnyXmlSchemaNode) node);

                } else if (node instanceof ContainerSchemaNode) {
                    property = processDataNodeContainer((ContainerSchemaNode) node, moduleName, models, isConfig,
                            schemaContext);

                } else {
                    throw new IllegalArgumentException("Unknown DataSchemaNode type: " + node.getClass());
                }

                property.putOpt(DESCRIPTION_KEY, node.getDescription());
                properties.put(name, property);
            }
        }
        return properties;
    }

    private JSONObject processLeafListNode(final LeafListSchemaNode listNode) throws JSONException {
        JSONObject props = new JSONObject();
        props.put(TYPE_KEY, ARRAY_TYPE);

        JSONObject itemsVal = new JSONObject();
        processTypeDef(listNode.getType(), itemsVal);
        props.put(ITEMS_KEY, itemsVal);

        ConstraintDefinition constraints = listNode.getConstraints();
        processConstraints(constraints, props);

        return props;
    }

    private JSONObject processChoiceNode(final ChoiceSchemaNode choiceNode, final String moduleName, final JSONObject models,
            final SchemaContext schemaContext) throws JSONException, IOException {

        Set<ChoiceCaseNode> cases = choiceNode.getCases();

        JSONArray choiceProps = new JSONArray();
        for (ChoiceCaseNode choiceCase : cases) {
            String choiceName = choiceCase.getQName().getLocalName();
            JSONObject choiceProp = processChildren(choiceCase.getChildNodes(), choiceCase.getQName(), moduleName,
                    models, schemaContext);
            JSONObject choiceObj = new JSONObject();
            choiceObj.put(choiceName, choiceProp);
            choiceObj.put(TYPE_KEY, OBJECT_TYPE);
            choiceProps.put(choiceObj);
        }

        JSONObject oneOfProps = new JSONObject();
        oneOfProps.put(ONE_OF_KEY, choiceProps);
        oneOfProps.put(TYPE_KEY, OBJECT_TYPE);

        return oneOfProps;
    }

    private static void processConstraints(final ConstraintDefinition constraints, final JSONObject props) throws JSONException {
        boolean isMandatory = constraints.isMandatory();
        props.put(REQUIRED_KEY, isMandatory);

        Integer minElements = constraints.getMinElements();
        Integer maxElements = constraints.getMaxElements();
        if (minElements != null) {
            props.put(MIN_ITEMS, minElements);
        }
        if (maxElements != null) {
            props.put(MAX_ITEMS, maxElements);
        }
    }

    private JSONObject processLeafNode(final LeafSchemaNode leafNode) throws JSONException {
        JSONObject property = new JSONObject();

        String leafDescription = leafNode.getDescription();
        property.put(DESCRIPTION_KEY, leafDescription);

        processConstraints(leafNode.getConstraints(), property);
        processTypeDef(leafNode.getType(), property);

        return property;
    }

    private static JSONObject processAnyXMLNode(final AnyXmlSchemaNode leafNode) throws JSONException {
        JSONObject property = new JSONObject();

        String leafDescription = leafNode.getDescription();
        property.put(DESCRIPTION_KEY, leafDescription);

        processConstraints(leafNode.getConstraints(), property);

        return property;
    }

    private void processTypeDef(final TypeDefinition<?> leafTypeDef, final JSONObject property) throws JSONException {
        if (leafTypeDef instanceof ExtendedType) {
            processExtendedType(leafTypeDef, property);
        } else if (leafTypeDef instanceof BinaryTypeDefinition) {
            processBinaryType((BinaryTypeDefinition) leafTypeDef, property);
        } else if (leafTypeDef instanceof BitsTypeDefinition) {
            processBitsType((BitsTypeDefinition) leafTypeDef, property);
        } else if (leafTypeDef instanceof EnumTypeDefinition) {
            processEnumType((EnumTypeDefinition) leafTypeDef, property);
        } else if (leafTypeDef instanceof IdentityrefTypeDefinition) {
            property.putOpt(TYPE_KEY,
                    ((IdentityrefTypeDefinition) leafTypeDef).getIdentity().getQName().getLocalName());
        } else if (leafTypeDef instanceof StringTypeDefinition) {
            processStringType((StringTypeDefinition) leafTypeDef, property);
        } else if (leafTypeDef instanceof UnionTypeDefinition) {
            processUnionType((UnionTypeDefinition) leafTypeDef, property);
        } else {
            String jsonType = jsonTypeFor(leafTypeDef);
            if (jsonType == null) {
                jsonType = "object";
            }
            property.putOpt(TYPE_KEY, jsonType);
        }
    }

    private void processExtendedType(final TypeDefinition<?> leafTypeDef, final JSONObject property) throws JSONException {
        TypeDefinition<?> leafBaseType = leafTypeDef.getBaseType();
        if (leafBaseType instanceof ExtendedType) {
            // recursively process an extended type until we hit a base type
            processExtendedType(leafBaseType, property);
        } else {
            List<LengthConstraint> lengthConstraints = ((ExtendedType) leafTypeDef).getLengthConstraints();
            for (LengthConstraint lengthConstraint : lengthConstraints) {
                Number min = lengthConstraint.getMin();
                Number max = lengthConstraint.getMax();
                property.putOpt(MIN_LENGTH_KEY, min);
                property.putOpt(MAX_LENGTH_KEY, max);
            }
            String jsonType = jsonTypeFor(leafBaseType);
            property.putOpt(TYPE_KEY, jsonType);
        }

    }

    private static void processBinaryType(final BinaryTypeDefinition binaryType, final JSONObject property) throws JSONException {
        property.put(TYPE_KEY, STRING);
        JSONObject media = new JSONObject();
        media.put(BINARY_ENCODING_KEY, BASE_64);
        property.put(MEDIA_KEY, media);
    }

    private static void processEnumType(final EnumTypeDefinition enumLeafType, final JSONObject property) throws JSONException {
        List<EnumPair> enumPairs = enumLeafType.getValues();
        List<String> enumNames = new ArrayList<>();
        for (EnumPair enumPair : enumPairs) {
            enumNames.add(enumPair.getName());
        }
        property.putOpt(ENUM, new JSONArray(enumNames));
    }

    private static void processBitsType(final BitsTypeDefinition bitsType, final JSONObject property) throws JSONException {
        property.put(TYPE_KEY, ARRAY_TYPE);
        property.put(MIN_ITEMS, 0);
        property.put(UNIQUE_ITEMS_KEY, true);
        JSONArray enumValues = new JSONArray();

        List<Bit> bits = bitsType.getBits();
        for (Bit bit : bits) {
            enumValues.put(bit.getName());
        }
        JSONObject itemsValue = new JSONObject();
        itemsValue.put(ENUM, enumValues);
        property.put(ITEMS_KEY, itemsValue);
    }

    private static void processStringType(final StringTypeDefinition stringType, final JSONObject property) throws JSONException {
        StringTypeDefinition type = stringType;
        List<LengthConstraint> lengthConstraints = stringType.getLengthConstraints();
        while (lengthConstraints.isEmpty() && type.getBaseType() != null) {
            type = type.getBaseType();
            lengthConstraints = type.getLengthConstraints();
        }

        // FIXME: json-schema is not expressive enough to capture min/max laternatives. We should find the true minimum
        //        and true maximum implied by the constraints and use that.
        for (LengthConstraint lengthConstraint : lengthConstraints) {
            Number min = lengthConstraint.getMin();
            Number max = lengthConstraint.getMax();
            property.putOpt(MIN_LENGTH_KEY, min);
            property.putOpt(MAX_LENGTH_KEY, max);
        }

        property.put(TYPE_KEY, STRING);
    }

    private static void processUnionType(final UnionTypeDefinition unionType, final JSONObject property) throws JSONException {
        StringBuilder type = new StringBuilder();
        for (TypeDefinition<?> typeDef : unionType.getTypes()) {
            if (type.length() > 0) {
                type.append(" or ");
            }
            type.append(jsonTypeFor(typeDef));
        }

        property.put(TYPE_KEY, type);
    }

    /**
     * Helper method to generate a pre-filled JSON schema object.
     */
    private static JSONObject getSchemaTemplate() throws JSONException {
        JSONObject schemaJSON = new JSONObject();
        schemaJSON.put(SCHEMA_KEY, SCHEMA_URL);

        return schemaJSON;
    }

}
