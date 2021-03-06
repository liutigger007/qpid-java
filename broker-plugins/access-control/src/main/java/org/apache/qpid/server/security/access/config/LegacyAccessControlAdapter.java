/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.security.access.config;

import static org.apache.qpid.server.security.access.config.ObjectType.EXCHANGE;
import static org.apache.qpid.server.security.access.config.ObjectType.METHOD;
import static org.apache.qpid.server.security.access.config.ObjectType.QUEUE;
import static org.apache.qpid.server.security.access.config.ObjectType.USER;
import static org.apache.qpid.server.security.access.config.LegacyOperation.ACCESS_LOGS;
import static org.apache.qpid.server.security.access.config.LegacyOperation.PUBLISH;
import static org.apache.qpid.server.security.access.config.LegacyOperation.PURGE;
import static org.apache.qpid.server.security.access.config.LegacyOperation.UPDATE;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.qpid.server.model.*;
import org.apache.qpid.server.queue.QueueConsumer;
import org.apache.qpid.server.security.Result;
import org.apache.qpid.server.security.access.Operation;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;

class LegacyAccessControlAdapter
{
    private static final Set<String> LOG_ACCESS_METHOD_NAMES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("getFile",
                                                                    "getFiles",
                                                                    "getAllFiles",
                                                                    "getLogEntries")));
    private static final Set<String> QUEUE_UPDATE_METHODS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("moveMessages",
                                                                    "copyMessages",
                                                                    "deleteMessages")));

    private static final Set<String> LEGACY_PREFERENCES_METHOD_NAMES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("getPreferences",
                                                                    "setPreferences",
                                                                    "deletePreferences")));

    private static final Set<String> BDB_VIRTUAL_HOST_NODE_OPERATIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("updateMutableConfig",
                                                                    "cleanLog",
                                                                    "checkpoint")));

    private static final Set<String> BROKER_CONFIGURE_OPERATIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("setJVMOptions",
                                                                    "dumpHeap",
                                                                    "performGC",
                                                                    "getThreadStackTraces",
                                                                    "findThreadStackTraces",
                                                                    "extractConfig")));
    private static final Set<String> VIRTUALHOST_UPDATE_OPERATIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("importMessageStore",
                                                                    "extractMessageStore")));

    private final LegacyAccessControl _accessControl;
    private final Model _model;

    LegacyAccessControlAdapter(final LegacyAccessControl accessControl,
                               final Model model)
    {
        _accessControl = accessControl;
        _model = model;
    }

    private Model getModel()
    {
        return _model;
    }

    Result authorise(final LegacyOperation operation, final ConfiguredObject<?> configuredObject)
    {
        if (isAllowedOperation(operation, configuredObject))
        {
            return Result.ALLOWED;
        }

        Class<? extends ConfiguredObject> categoryClass = configuredObject.getCategoryClass();
        ObjectType objectType = getACLObjectTypeManagingConfiguredObjectOfCategory(categoryClass);
        if (objectType == null)
        {
            throw new IllegalArgumentException("Cannot identify object type for category " + categoryClass );
        }

        ObjectProperties properties = getACLObjectProperties(configuredObject, operation);
        LegacyOperation authoriseOperation = validateAuthoriseOperation(operation, categoryClass);
        return _accessControl.authorise(authoriseOperation, objectType, properties);

    }

    private boolean isAllowedOperation(LegacyOperation operation, ConfiguredObject<?> configuredObject)
    {
        if (configuredObject instanceof Session && (operation == LegacyOperation.CREATE || operation == LegacyOperation.UPDATE
                                                    || operation == LegacyOperation.DELETE))
        {
            return true;

        }

        if (configuredObject instanceof Consumer && (operation == LegacyOperation.UPDATE || operation == LegacyOperation.DELETE))
        {
            return true;
        }

        if (configuredObject instanceof Connection && (operation == LegacyOperation.UPDATE || operation == LegacyOperation.DELETE))
        {
            return true;
        }

        return false;
    }

    private ObjectType getACLObjectTypeManagingConfiguredObjectOfCategory(Class<? extends ConfiguredObject> category)
    {
        if (Binding.class.isAssignableFrom(category))
        {
            return ObjectType.EXCHANGE;
        }
        else if (VirtualHostNode.class.isAssignableFrom(category))
        {
            return ObjectType.VIRTUALHOSTNODE;
        }
        else if (isBrokerType(category))
        {
            return ObjectType.BROKER;
        }
        else if (isVirtualHostType(category))
        {
            return ObjectType.VIRTUALHOST;
        }
        else if (Group.class.isAssignableFrom(category))
        {
            return ObjectType.GROUP;
        }
        else if (GroupMember.class.isAssignableFrom(category))
        {
            // UPDATE GROUP
            return ObjectType.GROUP;
        }
        else if (User.class.isAssignableFrom(category))
        {
            return ObjectType.USER;
        }
        else if (Queue.class.isAssignableFrom(category))
        {
            return ObjectType.QUEUE;
        }
        else if (Exchange.class.isAssignableFrom(category))
        {
            return ObjectType.EXCHANGE;
        }
        else if (Session.class.isAssignableFrom(category))
        {
            // PUBLISH EXCHANGE
            return ObjectType.EXCHANGE;
        }
        else if (Consumer.class.isAssignableFrom(category))
        {
            // CONSUME QUEUE
            return ObjectType.QUEUE;
        }
        else if (RemoteReplicationNode.class.isAssignableFrom(category))
        {
            // VHN permissions apply to remote nodes
            return ObjectType.VIRTUALHOSTNODE;
        }
        return null;
    }

    private boolean isVirtualHostType(Class<? extends ConfiguredObject> category)
    {
        return VirtualHost.class.isAssignableFrom(category) ||
               VirtualHostLogger.class.isAssignableFrom(category) ||
               VirtualHostLogInclusionRule.class.isAssignableFrom(category) ||
               VirtualHostAccessControlProvider.class.isAssignableFrom(category) ||
               Connection.class.isAssignableFrom(category);
    }

    private boolean isBrokerType(Class<? extends ConfiguredObject> category)
    {
        return Broker.class.isAssignableFrom(category) ||
               BrokerLogInclusionRule.class.isAssignableFrom(category) ||
               VirtualHostAlias.class.isAssignableFrom(category) ||
               ( !VirtualHostNode.class.isAssignableFrom(category) && getModel().getChildTypes(Broker.class).contains(category));
    }


    private ObjectProperties getACLObjectProperties(ConfiguredObject<?> configuredObject, LegacyOperation configuredObjectOperation)
    {
        String objectName = (String)configuredObject.getAttribute(ConfiguredObject.NAME);
        Class<? extends ConfiguredObject> configuredObjectType = configuredObject.getCategoryClass();
        ObjectProperties properties = new ObjectProperties(objectName);
        if (configuredObject instanceof Binding)
        {
            Exchange<?> exchange = (Exchange<?>)configuredObject.getParent(Exchange.class);
            Queue<?> queue = (Queue<?>)configuredObject.getParent(Queue.class);
            properties.setName((String)exchange.getAttribute(Exchange.NAME));
            properties.put(ObjectProperties.Property.QUEUE_NAME, (String)queue.getAttribute(Queue.NAME));
            properties.put(ObjectProperties.Property.ROUTING_KEY, (String)configuredObject.getAttribute(Binding.NAME));
            properties.put(ObjectProperties.Property.VIRTUALHOST_NAME, (String)queue.getParent(VirtualHost.class).getAttribute(VirtualHost.NAME));

            // The temporary attribute (inherited from the binding's queue) seems to exist to allow the user to
            // express rules about the binding of temporary queues (whose names cannot be predicted).
            properties.put(ObjectProperties.Property.TEMPORARY, queue.getAttribute(Queue.LIFETIME_POLICY) != LifetimePolicy.PERMANENT);
            properties.put(ObjectProperties.Property.DURABLE, (Boolean)queue.getAttribute(Queue.DURABLE));
        }
        else if (configuredObject instanceof Queue)
        {
            setQueueProperties(configuredObject, properties);
        }
        else if (configuredObject instanceof Exchange)
        {
            Object lifeTimePolicy = configuredObject.getAttribute(ConfiguredObject.LIFETIME_POLICY);
            properties.put(ObjectProperties.Property.AUTO_DELETE, lifeTimePolicy != LifetimePolicy.PERMANENT);
            properties.put(ObjectProperties.Property.TEMPORARY, lifeTimePolicy != LifetimePolicy.PERMANENT);
            properties.put(ObjectProperties.Property.DURABLE, (Boolean) configuredObject.getAttribute(ConfiguredObject.DURABLE));
            properties.put(ObjectProperties.Property.TYPE, (String) configuredObject.getAttribute(Exchange.TYPE));
            VirtualHost virtualHost = configuredObject.getParent(VirtualHost.class);
            properties.put(ObjectProperties.Property.VIRTUALHOST_NAME, (String)virtualHost.getAttribute(virtualHost.NAME));
        }
        else if (configuredObject instanceof QueueConsumer)
        {
            Queue<?> queue = (Queue<?>)configuredObject.getParent(Queue.class);
            setQueueProperties(queue, properties);
        }
        else if (isBrokerType(configuredObjectType))
        {
            String description = String.format("%s %s '%s'",
                                               configuredObjectOperation == null? null : configuredObjectOperation.name().toLowerCase(),
                                               configuredObjectType == null ? null : configuredObjectType.getSimpleName().toLowerCase(),
                                               objectName);
            properties = new OperationLoggingDetails(description);
        }
        else if (isVirtualHostType(configuredObjectType))
        {
            ConfiguredObject<?> virtualHost = getModel().getAncestor(VirtualHost.class, configuredObject);
            properties = new ObjectProperties((String)virtualHost.getAttribute(ConfiguredObject.NAME));
        }
        return properties;
    }

    private void setQueueProperties(ConfiguredObject<?>  queue, ObjectProperties properties)
    {
        properties.setName((String)queue.getAttribute(Exchange.NAME));
        Object lifeTimePolicy = queue.getAttribute(ConfiguredObject.LIFETIME_POLICY);
        properties.put(ObjectProperties.Property.AUTO_DELETE, lifeTimePolicy != LifetimePolicy.PERMANENT);
        properties.put(ObjectProperties.Property.TEMPORARY, lifeTimePolicy != LifetimePolicy.PERMANENT);
        properties.put(ObjectProperties.Property.DURABLE, (Boolean)queue.getAttribute(ConfiguredObject.DURABLE));
        properties.put(ObjectProperties.Property.EXCLUSIVE, queue.getAttribute(Queue.EXCLUSIVE) != ExclusivityPolicy.NONE);
        Object alternateExchange = queue.getAttribute(Queue.ALTERNATE_EXCHANGE);
        if (alternateExchange != null)
        {
            String name = alternateExchange instanceof ConfiguredObject ?
                    (String)((ConfiguredObject)alternateExchange).getAttribute(ConfiguredObject.NAME) :
                    String.valueOf(alternateExchange);
            properties.put(ObjectProperties.Property.ALTERNATE, name);
        }
        String owner = (String)queue.getAttribute(Queue.OWNER);
        if (owner != null)
        {
            properties.put(ObjectProperties.Property.OWNER, owner);
        }
        VirtualHost virtualHost = queue.getParent(VirtualHost.class);
        properties.put(ObjectProperties.Property.VIRTUALHOST_NAME, (String)virtualHost.getAttribute(virtualHost.NAME));
    }


    private LegacyOperation validateAuthoriseOperation(LegacyOperation operation, Class<? extends ConfiguredObject> category)
    {
        if (operation == LegacyOperation.CREATE || operation == LegacyOperation.UPDATE)
        {
            if (Binding.class.isAssignableFrom(category))
            {
                // CREATE BINDING is transformed into BIND EXCHANGE rule
                return LegacyOperation.BIND;
            }
            else if (Consumer.class.isAssignableFrom(category))
            {
                // CREATE CONSUMER is transformed into CONSUME QUEUE rule
                return LegacyOperation.CONSUME;
            }
            else if (GroupMember.class.isAssignableFrom(category))
            {
                // CREATE GROUP MEMBER is transformed into UPDATE GROUP rule
                return LegacyOperation.UPDATE;
            }
            else if (isBrokerType(category))
            {
                // CREATE/UPDATE broker child is transformed into CONFIGURE BROKER rule
                return LegacyOperation.CONFIGURE;
            }
        }
        else if (operation == LegacyOperation.DELETE)
        {
            if (Binding.class.isAssignableFrom(category))
            {
                // DELETE BINDING is transformed into UNBIND EXCHANGE rule
                return LegacyOperation.UNBIND;
            }
            else if (isBrokerType(category))
            {
                // DELETE broker child is transformed into CONFIGURE BROKER rule
                return LegacyOperation.CONFIGURE;

            }
            else if (GroupMember.class.isAssignableFrom(category))
            {
                // DELETE GROUP MEMBER is transformed into UPDATE GROUP rule
                return LegacyOperation.UPDATE;
            }
        }
        return operation;
    }

    Result authoriseAction(final ConfiguredObject<?> configuredObject,
                           String actionName,
                           final Map<String, Object> arguments)
    {
        Class<? extends ConfiguredObject> categoryClass = configuredObject.getCategoryClass();
        if(categoryClass == Exchange.class)
        {
            Exchange exchange = (Exchange) configuredObject;
            if("publish".equals(actionName))
            {

                final ObjectProperties _props =
                        new ObjectProperties(exchange.getParent(VirtualHost.class).getName(), exchange.getName(), (String)arguments.get("routingKey"), (Boolean)arguments.get("immediate"));
                return _accessControl.authorise(PUBLISH, EXCHANGE, _props);
            }
        }
        else if(categoryClass == VirtualHost.class)
        {
            if("connect".equals(actionName))
            {
                String virtualHostName = configuredObject.getName();
                ObjectProperties properties = new ObjectProperties(virtualHostName);
                properties.put(ObjectProperties.Property.VIRTUALHOST_NAME, virtualHostName);
                return _accessControl.authorise(LegacyOperation.ACCESS, ObjectType.VIRTUALHOST, properties);
            }
        }
        else if(categoryClass == Broker.class)
        {
            if("manage".equals(actionName))
            {
                return _accessControl.authorise(LegacyOperation.ACCESS, ObjectType.MANAGEMENT, ObjectProperties.EMPTY);
            }
            else if("CONFIGURE".equals(actionName) || "SHUTDOWN".equals(actionName))
            {
                return _accessControl.authorise(LegacyOperation.valueOf(actionName), ObjectType.BROKER, ObjectProperties.EMPTY);
            }
        }
        else if(categoryClass == Queue.class)
        {
            Queue queue = (Queue) configuredObject;
            if("publish".equals(actionName))
            {

                final ObjectProperties _props =
                        new ObjectProperties(queue.getParent(VirtualHost.class).getName(), "", queue.getName(), (Boolean)arguments.get("immediate"));
                return _accessControl.authorise(PUBLISH, EXCHANGE, _props);
            }
        }

        return Result.DEFER;

    }

    Result authoriseMethod(final ConfiguredObject<?> configuredObject,
                           final String methodName,
                           final Map<String, Object> arguments)
    {
        Class<? extends ConfiguredObject> categoryClass = configuredObject.getCategoryClass();
        if(categoryClass == Queue.class)
        {
            Queue queue = (Queue) configuredObject;
            final ObjectProperties properties = new ObjectProperties();
            if("clearQueue".equals(methodName))
            {
                setQueueProperties(queue, properties);
                return _accessControl.authorise(PURGE, QUEUE, properties);
            }
            else if(QUEUE_UPDATE_METHODS.contains(methodName))
            {
                VirtualHost virtualHost = queue.getVirtualHost();
                final String virtualHostName = virtualHost.getName();
                properties.setName(methodName);
                properties.put(ObjectProperties.Property.COMPONENT, "VirtualHost.Queue");
                properties.put(ObjectProperties.Property.VIRTUALHOST_NAME, virtualHostName);
                return _accessControl.authorise(LegacyOperation.UPDATE, METHOD, properties);

            }
            else if("publish".equals(methodName))
            {

                final ObjectProperties _props =
                        new ObjectProperties(queue.getParent(VirtualHost.class).getName(), "", queue.getName(), (Boolean)arguments.get("immediate"));
                return _accessControl.authorise(PUBLISH, EXCHANGE, _props);
            }
        }
        else if(categoryClass == BrokerLogger.class)
        {
            if(LOG_ACCESS_METHOD_NAMES.contains(methodName))
            {
                return _accessControl.authorise(ACCESS_LOGS, ObjectType.BROKER, ObjectProperties.EMPTY);
            }
        }
        else if(categoryClass == VirtualHostLogger.class)
        {
            VirtualHostLogger logger = (VirtualHostLogger)configuredObject;
            if(LOG_ACCESS_METHOD_NAMES.contains(methodName))
            {
                return _accessControl.authorise(ACCESS_LOGS,
                                                ObjectType.VIRTUALHOST,
                                                new ObjectProperties(logger.getParent(VirtualHost.class).getName()));
            }
        }
        else if(categoryClass == AuthenticationProvider.class)
        {
            if(LEGACY_PREFERENCES_METHOD_NAMES.contains(methodName))
            {
                if(arguments.get("userId") instanceof String)
                {
                    String userName = (String) arguments.get("userId");
                    AuthenticatedPrincipal principal = AuthenticatedPrincipal.getCurrentUser();
                    if (principal != null && principal.getName().equals(userName))
                    {
                        // allow user to update its own data
                        return Result.ALLOWED;
                    }
                    else
                    {
                        return _accessControl.authorise(UPDATE,
                                                        USER,
                                                        new ObjectProperties(userName));
                    }
                }
            }
        }
        else if(categoryClass == VirtualHostNode.class)
        {
            if(BDB_VIRTUAL_HOST_NODE_OPERATIONS.contains(methodName))
            {
                ObjectProperties properties = getACLObjectProperties(configuredObject.getParent(Broker.class), LegacyOperation.UPDATE);
                return _accessControl.authorise(LegacyOperation.UPDATE, ObjectType.BROKER, properties);
            }
        }
        else if(categoryClass == Broker.class)
        {
            if(BROKER_CONFIGURE_OPERATIONS.contains(methodName))
            {
                _accessControl.authorise(LegacyOperation.CONFIGURE, ObjectType.BROKER, ObjectProperties.EMPTY);
            }
            else if("initiateShutdown".equals(methodName))
            {
                _accessControl.authorise(LegacyOperation.SHUTDOWN, ObjectType.BROKER, ObjectProperties.EMPTY);
            }

        }
        else if(categoryClass == VirtualHost.class)
        {
            if(VIRTUALHOST_UPDATE_OPERATIONS.contains(methodName))
            {
                authorise(LegacyOperation.UPDATE, configuredObject);
            }
        }
        return Result.ALLOWED;

    }


    Result authorise(final Operation operation,
                     final ConfiguredObject<?> configuredObject,
                     final Map<String, Object> arguments)
    {
        switch(operation.getType())
        {
            case CREATE:
                return authorise(LegacyOperation.CREATE, configuredObject);
            case UPDATE:
                return authorise(LegacyOperation.UPDATE, configuredObject);
            case DELETE:
                return authorise(LegacyOperation.DELETE, configuredObject);
            case METHOD:
                return authoriseMethod(configuredObject, operation.getName(), arguments);
            case ACTION:
                return authoriseAction(configuredObject, operation.getName(), arguments);
            case DISCOVER:
            case READ:
                return Result.DEFER;

            default:
        }
        return null;
    }
}
