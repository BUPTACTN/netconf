/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.restconf.utils.parser;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.text.ParseException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPoint;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.netconf.md.sal.rest.schema.SchemaExportContext;
import org.opendaylight.netconf.sal.restconf.impl.InstanceIdentifierContext;
import org.opendaylight.netconf.sal.restconf.impl.RestconfDocumentedException;
import org.opendaylight.netconf.sal.restconf.impl.RestconfError.ErrorTag;
import org.opendaylight.netconf.sal.restconf.impl.RestconfError.ErrorType;
import org.opendaylight.restconf.parser.IdentifierCodec;
import org.opendaylight.restconf.utils.RestconfConstants;
import org.opendaylight.restconf.utils.validation.RestconfValidation;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.util.DataSchemaContextNode;
import org.opendaylight.yangtools.yang.data.util.DataSchemaContextTree;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Util class for parsing identifier
 *
 */
public final class ParserIdentifier {

    private static final Logger LOG = LoggerFactory.getLogger(ParserIdentifier.class);

    /**
     * Make {@link InstanceIdentifierContext} from identifier.
     *
     * @param identifier
     *            - path identifier
     * @param schemaContext
     *            - {@link SchemaContext}
     * @return {@link InstanceIdentifierContext}
     */
    public static InstanceIdentifierContext<?> toInstanceIdentifier(final String identifier,
            final SchemaContext schemaContext) {
        final YangInstanceIdentifier deserialize;
        if (identifier.contains(RestconfConstants.MOUNT)) {
            final String mountPointId = identifier.substring(0, identifier.indexOf("/" + RestconfConstants.MOUNT));
            deserialize = IdentifierCodec.deserialize(mountPointId, schemaContext);
        } else {
            deserialize = IdentifierCodec.deserialize(identifier, schemaContext);
        }
        final DataSchemaContextNode<?> child = DataSchemaContextTree.from(schemaContext).getChild(deserialize);
        return new InstanceIdentifierContext<SchemaNode>(deserialize, child.getDataSchemaNode(), null, schemaContext);
    }

    /**
     * Make a {@link QName} from identifier
     *
     * @param identifier
     *            - path parameter
     * @return {@link QName}
     */
    public static QName makeQNameFromIdentifier(final String identifier) {
        final int mountIndex = identifier.indexOf(RestconfConstants.MOUNT);
        String moduleNameAndRevision = "";
        if (mountIndex >= 0) {
            moduleNameAndRevision = identifier.substring(mountIndex + RestconfConstants.MOUNT.length());
        } else {
            moduleNameAndRevision = identifier;
        }

        final Splitter splitter = Splitter.on("/").omitEmptyStrings();
        final Iterable<String> split = splitter.split(moduleNameAndRevision);
        final List<String> pathArgs = Lists.<String> newArrayList(split);
        if (pathArgs.size() < 2) {
            LOG.debug("URI has bad format. It should be \'moduleName/yyyy-MM-dd\' " + identifier);
            throw new RestconfDocumentedException(
                    "URI has bad format. End of URI should be in format \'moduleName/yyyy-MM-dd\'", ErrorType.PROTOCOL,
                    ErrorTag.INVALID_VALUE);
        }

        try {
            final String moduleName = pathArgs.get(0);
            final String revision = pathArgs.get(1);
            final Date moduleRevision = RestconfConstants.REVISION_FORMAT.parse(revision);

            return QName.create(null, moduleRevision, moduleName);
        } catch (final ParseException e) {
            LOG.debug("URI has bad format. It should be \'moduleName/yyyy-MM-dd\' " + identifier);
            throw new RestconfDocumentedException("URI has bad format. It should be \'moduleName/yyyy-MM-dd\'",
                    ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
        }
    }

    /**
     * Parsing {@link Module} module by {@link String} module name and
     * {@link Date} revision and from the parsed module create
     * {@link SchemaExportContext}
     *
     * @param schemaContext
     *            - {@link SchemaContext}
     * @param identifier
     *            - path parameter
     * @param domMountPointService
     *            - {@link DOMMountPointService}
     * @return {@link SchemaExportContext}
     */
    public static SchemaExportContext toSchemaExportContextFromIdentifier(final SchemaContext schemaContext,
            final String identifier, final DOMMountPointService domMountPointService) {
        final Iterable<String> pathComponents = RestconfConstants.SLASH_SPLITTER.split(identifier);
        final Iterator<String> componentIter = pathComponents.iterator();
        if (!Iterables.contains(pathComponents, RestconfConstants.MOUNT)) {
            final String moduleName = RestconfValidation.validateAndGetModulName(componentIter);
            final Date revision = RestconfValidation.validateAndGetRevision(componentIter);
            final Module module = schemaContext.findModuleByName(moduleName, revision);
            return new SchemaExportContext(schemaContext, module);
        } else {
            final StringBuilder pathBuilder = new StringBuilder();
            while (componentIter.hasNext()) {
                final String current = componentIter.next();
                pathBuilder.append("/");
                pathBuilder.append(current);
                if (RestconfConstants.MOUNT.equals(current)) {
                    break;
                }
            }
            final InstanceIdentifierContext<?> point = ParserIdentifier
                    .toInstanceIdentifier(pathBuilder.toString(), schemaContext);
            final DOMMountPoint mountPoint = domMountPointService.getMountPoint(point.getInstanceIdentifier()).get();
            final String moduleName = RestconfValidation.validateAndGetModulName(componentIter);
            final Date revision = RestconfValidation.validateAndGetRevision(componentIter);
            final Module module = mountPoint.getSchemaContext().findModuleByName(moduleName, revision);
            return new SchemaExportContext(mountPoint.getSchemaContext(), module);
        }
    }
}
