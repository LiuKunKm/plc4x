/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package org.apache.plc4x.java.knxnetip.protocol;

import io.netty.channel.socket.DatagramChannel;
import org.apache.commons.codec.binary.Hex;
import org.apache.plc4x.java.api.exceptions.PlcRuntimeException;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.model.PlcConsumerRegistration;
import org.apache.plc4x.java.api.model.PlcField;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.*;
import org.apache.plc4x.java.knxnetip.context.KnxNetIpDriverContext;
import org.apache.plc4x.java.knxnetip.ets5.model.Ets5Model;
import org.apache.plc4x.java.knxnetip.ets5.model.GroupAddress;
import org.apache.plc4x.java.knxnetip.field.KnxNetIpField;
import org.apache.plc4x.java.knxnetip.model.KnxNetIpSubscriptionHandle;
import org.apache.plc4x.java.knxnetip.readwrite.KNXGroupAddress;
import org.apache.plc4x.java.knxnetip.readwrite.KNXGroupAddress2Level;
import org.apache.plc4x.java.knxnetip.readwrite.KNXGroupAddress3Level;
import org.apache.plc4x.java.knxnetip.readwrite.KNXGroupAddressFreeLevel;
import org.apache.plc4x.java.knxnetip.readwrite.io.KNXGroupAddressIO;
import org.apache.plc4x.java.knxnetip.readwrite.io.KnxDatapointIO;
import org.apache.plc4x.java.knxnetip.readwrite.types.*;
import org.apache.plc4x.java.spi.ConversationContext;
import org.apache.plc4x.java.spi.Plc4xProtocolBase;
import org.apache.plc4x.java.knxnetip.readwrite.*;
import org.apache.plc4x.java.spi.context.DriverContext;
import org.apache.plc4x.java.spi.generation.ParseException;
import org.apache.plc4x.java.spi.generation.ReadBuffer;
import org.apache.plc4x.java.spi.generation.WriteBuffer;
import org.apache.plc4x.java.spi.messages.*;
import org.apache.plc4x.java.spi.messages.utils.ResponseItem;
import org.apache.plc4x.java.spi.model.DefaultPlcConsumerRegistration;
import org.apache.plc4x.java.spi.model.InternalPlcSubscriptionHandle;
import org.apache.plc4x.java.spi.transaction.RequestTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class KnxNetIpProtocolLogic extends Plc4xProtocolBase<KNXNetIPMessage> implements PlcSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnxNetIpProtocolLogic.class);
    public static final Duration REQUEST_TIMEOUT = Duration.ofMillis(10000);

    private KnxNetIpDriverContext knxNetIpDriverContext;
    private Timer connectionStateTimer;
    private static final AtomicInteger sequenceCounter = new AtomicInteger(0);
    private RequestTransactionManager tm;

    private Map<DefaultPlcConsumerRegistration, Consumer<PlcSubscriptionEvent>> consumers = new ConcurrentHashMap<>();

    @Override
    public void setDriverContext(DriverContext driverContext) {
        super.setDriverContext(driverContext);
        this.knxNetIpDriverContext = (KnxNetIpDriverContext) driverContext;

        // Initialize Transaction Manager.
        // Until the number of concurrent requests is successfully negotiated we set it to a
        // maximum of only one request being able to be sent at a time. During the login process
        // No concurrent requests can be sent anyway. It will be updated when receiving the
        // S7ParameterSetupCommunication response.
        this.tm = new RequestTransactionManager(1);
    }

    @Override
    public void onConnect(ConversationContext<KNXNetIPMessage> context) {
        // Only the UDP transport supports login.
        if(context.getChannel() instanceof DatagramChannel) {
            LOGGER.info("KNX Driver running in ACTIVE mode.");
            knxNetIpDriverContext.setPassiveMode(false);

            DatagramChannel channel = (DatagramChannel) context.getChannel();
            final InetSocketAddress localSocketAddress = channel.localAddress();
            knxNetIpDriverContext.setLocalIPAddress(new IPAddress(localSocketAddress.getAddress().getAddress()));
            knxNetIpDriverContext.setLocalPort(localSocketAddress.getPort());

            // First send out a search request
            // REMARK: This might be optional ... usually we would send a search request to ip 224.0.23.12
            // Any KNX Gateway will respond with a search response. We're currently directly sending to the
            // known gateway address, so it's sort of pointless, but at least only one device will respond.
            LOGGER.info("Sending KNXnet/IP Search Request.");
            SearchRequest searchRequest = new SearchRequest(
                new HPAIDiscoveryEndpoint(HostProtocolCode.IPV4_UDP,
                    knxNetIpDriverContext.getLocalIPAddress(), knxNetIpDriverContext.getLocalPort()));
            context.sendRequest(searchRequest)
                .expectResponse(KNXNetIPMessage.class, Duration.ofMillis(1000))
                .check(p -> p instanceof SearchResponse)
                .unwrap(p -> (SearchResponse) p)
                .handle(searchResponse -> {
                    LOGGER.info("Got KNXnet/IP Search Response.");
                    // Check if this device supports tunneling services.
                    final ServiceId tunnelingService = Arrays.stream(searchResponse.getDibSuppSvcFamilies().getServiceIds()).filter(serviceId -> serviceId instanceof KnxNetIpTunneling).findFirst().orElse(null);

                    // If this device supports this type of service, tell the driver, we found a suitable device.
                    if (tunnelingService != null) {
                        // Extract the required information form the search request.
                        knxNetIpDriverContext.setGatewayAddress(searchResponse.getDibDeviceInfo().getKnxAddress());
                        knxNetIpDriverContext.setGatewayName(new String(searchResponse.getDibDeviceInfo().getDeviceFriendlyName()).trim());

                        LOGGER.info(String.format("Found KNXnet/IP Gateway '%s' with KNX address '%d.%d.%d'",
                            knxNetIpDriverContext.getGatewayName(),
                            knxNetIpDriverContext.getGatewayAddress().getMainGroup(),
                            knxNetIpDriverContext.getGatewayAddress().getMiddleGroup(),
                            knxNetIpDriverContext.getGatewayAddress().getSubGroup()));

                        // Next send a connection request to the gateway.
                        ConnectionRequest connectionRequest = new ConnectionRequest(
                            new HPAIDiscoveryEndpoint(HostProtocolCode.IPV4_UDP,
                                knxNetIpDriverContext.getLocalIPAddress(), knxNetIpDriverContext.getLocalPort()),
                            new HPAIDataEndpoint(HostProtocolCode.IPV4_UDP,
                                knxNetIpDriverContext.getLocalIPAddress(), knxNetIpDriverContext.getLocalPort()),
                            new ConnectionRequestInformationTunnelConnection(
                                knxNetIpDriverContext.getTunnelConnectionType()));
                        LOGGER.info("Sending KNXnet/IP Connection Request.");
                        context.sendRequest(connectionRequest)
                            .expectResponse(KNXNetIPMessage.class, Duration.ofMillis(1000))
                            .check(p -> p instanceof ConnectionResponse)
                            .unwrap(p -> (ConnectionResponse) p)
                            .handle(connectionResponse -> {
                                // Remember the communication channel id.
                                knxNetIpDriverContext.setCommunicationChannelId(
                                    connectionResponse.getCommunicationChannelId());

                                LOGGER.info(String.format("Received KNXnet/IP Connection Response (Connection Id %s)",
                                    knxNetIpDriverContext.getCommunicationChannelId()));

                                // Check if everything went well.
                                Status status = connectionResponse.getStatus();
                                if (status == Status.NO_ERROR) {
                                    final ConnectionResponseDataBlockTunnelConnection tunnelConnectionDataBlock =
                                        (ConnectionResponseDataBlockTunnelConnection) connectionResponse.getConnectionResponseDataBlock();
                                    // Save the KNX Address the Gateway assigned to this connection.
                                    knxNetIpDriverContext.setClientKnxAddress(tunnelConnectionDataBlock.getKnxAddress());

                                    final KNXAddress gatewayAddress = knxNetIpDriverContext.getGatewayAddress();
                                    final KNXAddress clientKnxAddress = knxNetIpDriverContext.getClientKnxAddress();
                                    LOGGER.info(String.format("Successfully connected to KNXnet/IP Gateway '%s' with KNX address '%d.%d.%d' got assigned client KNX address '%d.%d.%d'",
                                        knxNetIpDriverContext.getGatewayName(),
                                        gatewayAddress.getMainGroup(), gatewayAddress.getMiddleGroup(),
                                        gatewayAddress.getSubGroup(), clientKnxAddress.getMainGroup(),
                                        clientKnxAddress.getMiddleGroup(), clientKnxAddress.getSubGroup()));

                                    // Send an event that connection setup is complete.
                                    context.fireConnected();

                                    // Start a timer to check the connection state every 60 seconds.
                                    // This keeps the connection open if no data is transported.
                                    // Otherwise the gateway will terminate the connection.
                                    connectionStateTimer = new Timer();
                                    connectionStateTimer.scheduleAtFixedRate(new TimerTask() {
                                        @Override
                                        public void run() {
                                            ConnectionStateRequest connectionStateRequest =
                                                new ConnectionStateRequest(
                                                    knxNetIpDriverContext.getCommunicationChannelId(),
                                                    new HPAIControlEndpoint(HostProtocolCode.IPV4_UDP,
                                                        knxNetIpDriverContext.getLocalIPAddress(),
                                                        knxNetIpDriverContext.getLocalPort()));
                                            context.sendRequest(connectionStateRequest)
                                                .expectResponse(KNXNetIPMessage.class, Duration.ofMillis(1000))
                                                .check(p -> p instanceof ConnectionStateResponse)
                                                .unwrap(p -> (ConnectionStateResponse) p)
                                                .handle(connectionStateResponse -> {
                                                    if (connectionStateResponse.getStatus() != Status.NO_ERROR) {
                                                        if (connectionStateResponse.getStatus() != null) {
                                                            LOGGER.error(String.format("Connection state problems. Got %s",
                                                                connectionStateResponse.getStatus().name()));
                                                        } else {
                                                            LOGGER.error("Connection state problems. Got no status information.");
                                                        }
                                                    }
                                                });
                                        }
                                    }, 60000, 60000);
                                } else {
                                    // The connection request wasn't successful.
                                    LOGGER.error(String.format(
                                        "Not connected to KNXnet/IP Gateway '%s' with KNX address '%d.%d.%d' got status: '%s'",
                                        knxNetIpDriverContext.getGatewayName(),
                                        knxNetIpDriverContext.getGatewayAddress().getMainGroup(),
                                        knxNetIpDriverContext.getGatewayAddress().getMiddleGroup(),
                                        knxNetIpDriverContext.getGatewayAddress().getSubGroup(), status.toString()));
                                    // TODO: Actively disconnect
                                }
                            });
                    } else {
                        // This device doesn't support tunneling ... do some error handling.
                        LOGGER.error("Not connected to KNCnet/IP Gateway. The device doesn't support Tunneling.");
                        // TODO: Actively disconnect
                    }
                });
        }
        // This usually when we're running a passive mode river.
        else {
            LOGGER.info("KNX Driver running in PASSIVE mode.");
            knxNetIpDriverContext.setPassiveMode(true);

            // No login required, just confirm that we're connected.
            context.fireConnected();
        }
    }

    @Override
    public void onDisconnect(ConversationContext<KNXNetIPMessage> context) {
        // Cancel the timer for sending connection state requests.
        connectionStateTimer.cancel();

        DisconnectRequest disconnectRequest = new DisconnectRequest(knxNetIpDriverContext.getCommunicationChannelId(),
            new HPAIControlEndpoint(HostProtocolCode.IPV4_UDP,
                knxNetIpDriverContext.getLocalIPAddress(), knxNetIpDriverContext.getLocalPort()));
        context.sendRequest(disconnectRequest)
            .expectResponse(KNXNetIPMessage.class, Duration.ofMillis(1000))
            .check(p -> p instanceof DisconnectResponse)
            .unwrap(p -> (DisconnectResponse) p)
            .handle(disconnectResponse -> {
                // In general we should probably check if the disconnect was successful, but in
                // the end we couldn't do much if the disconnect would fail.
                final String gatewayName = knxNetIpDriverContext.getGatewayName();
                final KNXAddress gatewayAddress = knxNetIpDriverContext.getGatewayAddress();
                LOGGER.info(String.format("Disconnected from KNX Gateway '%s' with KNX address '%d.%d.%d'", gatewayName,
                    gatewayAddress.getMainGroup(), gatewayAddress.getMiddleGroup(), gatewayAddress.getSubGroup()));

                // Send an event that connection disconnect is complete.
                context.fireDisconnected();
            });
    }

    @Override
    public CompletableFuture<PlcWriteResponse> write(PlcWriteRequest writeRequest) {
        CompletableFuture<PlcWriteResponse> future = new CompletableFuture<>();
        DefaultPlcWriteRequest request = (DefaultPlcWriteRequest) writeRequest;

        // As the KNX driver is using the SingleFieldOptimizer, each request here will have
        // only one item.
        final Optional<String> first = request.getFieldNames().stream().findFirst();
        if (first.isPresent()) {
            String fieldName = first.get();
            final KnxNetIpField field = (KnxNetIpField) request.getField(fieldName);
            byte[] destinationAddress = toKnxAddressData(field);
            if(sequenceCounter.get() == Short.MAX_VALUE) {
                sequenceCounter.set(0);
            }

            // Convert the PlcValue to byte data.
            final PlcValue value = request.getPlcValue(fieldName);
            byte dataFirstByte = 0;
            byte[] data = null;
            final Ets5Model ets5Model = knxNetIpDriverContext.getEts5Model();
            if(ets5Model != null) {
                final String destinationAddressString = ets5Model.parseGroupAddress(destinationAddress);
                final GroupAddress groupAddress = ets5Model.getGroupAddresses().get(destinationAddressString);
                if((groupAddress == null) || (groupAddress.getType() == null)) {
                    future.completeExceptionally(new PlcRuntimeException(
                        "ETS5 model didn't specify group address '" + destinationAddressString +
                            "' or didn't define a type for it."));
                    return future;
                }

                // Use the data in the ets5 model to correctly check and serialize the PlcValue
                try {
                    final WriteBuffer writeBuffer = KnxDatapointIO.staticSerialize(value,
                        groupAddress.getType().getMainType(), groupAddress.getType().getSubType());
                    final byte[] serialized = writeBuffer.getData();
                    dataFirstByte = serialized[0];
                    data = new byte[serialized.length - 1];
                    System.arraycopy(serialized, 1, data, 0, serialized.length - 1);
                } catch (ParseException e) {
                    future.completeExceptionally(new PlcRuntimeException("Error serializing PlcValue.", e));
                    return future;
                }
            } else  {
                if (value.isByte()) {
                    if((value.getByte() > 63) || (value.getByte() < 0)) {
                        future.completeExceptionally(new PlcRuntimeException(
                            "If no ETS5 model is provided, value of the first byte must be between 0 and 63."));
                        return future;
                    }
                    dataFirstByte = value.getByte();
                }
                else if(value.isList()) {
                    // Check each item of the list, if it's also a byte.
                    List<? extends PlcValue> list = value.getList();
                    data = new byte[list.size() - 1];
                    boolean allValuesAreBytes = !list.isEmpty();
                    int numByte = 0;
                    for (PlcValue plcValue : list) {
                        if(numByte == 0) {
                            if(!plcValue.isByte() && (plcValue.getByte() > 63) || (plcValue.getByte() < 0)) {
                                allValuesAreBytes = false;
                                break;
                            }
                            dataFirstByte = plcValue.getByte();
                        } else {
                            if (!plcValue.isByte()) {
                                allValuesAreBytes = false;
                                break;
                            }
                            data[numByte - 1] = plcValue.getByte();
                        }
                        numByte++;
                    }
                    if(!allValuesAreBytes) {
                        future.completeExceptionally(new PlcRuntimeException("If no ETS5 model is provided, the only supported type for writing data is writing of single byte or list of bytes and the value of the first byte must be between 0 and 63."));
                        return future;
                    }
                }
                else {
                    future.completeExceptionally(new PlcRuntimeException("If no ETS5 model is provided, the only supported type for writing data is writing of single byte or list of bytes."));
                    return future;
                }
            }

            final short communicationChannelId = knxNetIpDriverContext.getCommunicationChannelId();
            // Prepare the knx request message.
            TunnelingRequest knxRequest = new TunnelingRequest(
                new TunnelingRequestDataBlock(communicationChannelId,
                    (short) sequenceCounter.getAndIncrement()),
                new CEMIDataReq((short) 0, new CEMIAdditionalInformation[0],
                    new CEMIDataFrame(true, false, true, true, CEMIPriority.LOW, false, false, true, (byte) 6,
                        (byte) 0, knxNetIpDriverContext.getClientKnxAddress(), destinationAddress,
                        (short) ((data != null) ? data.length + 1 : 1), TPCI.UNNUMBERED_DATA_PACKET, (byte) 0,
                        APCI.GROUP_VALUE_WRITE_PDU, dataFirstByte, data)
                ));

            // Start a new request-transaction (Is ended in the response-handler)
            RequestTransactionManager.RequestTransaction transaction = tm.startRequest();
            // Start a new request-transaction (Is ended in the response-handler)
            transaction.submit(() -> context.sendRequest(knxRequest)
                .expectResponse(KNXNetIPMessage.class, REQUEST_TIMEOUT)
                .onTimeout(future::completeExceptionally)
                .onError((tr, e) -> future.completeExceptionally(e))
                // Technically this is not 100% correct, as actually an Ack would be received
                // which is instantly followed by this request. However the Ack was always OK
                // no matter what I threw at the Gateway, so we'll make the code a lot simpler
                // and assume the Request is the response to our request.
                .check(tr -> tr instanceof TunnelingRequest)
                .unwrap(tr -> ((TunnelingRequest) tr))
                .check(tr -> tr.getCemi() instanceof CEMIDataCon)
                .check(tr -> (tr.getTunnelingRequestDataBlock().getCommunicationChannelId() == communicationChannelId) &&
                    (tr.getTunnelingRequestDataBlock().getSequenceCounter() == knxRequest.getTunnelingRequestDataBlock().getSequenceCounter()))
                .handle(tr -> {
                    // In this case all wen't well.
                    PlcResponseCode responseCode = PlcResponseCode.OK;
                    // Prepare the response.
                    PlcWriteResponse response = new DefaultPlcWriteResponse(request,
                        Collections.singletonMap(fieldName, responseCode));

                    future.complete(response);

                    // Send Ack back.
                    TunnelingResponse ack = new TunnelingResponse(new TunnelingResponseDataBlock(communicationChannelId,
                        tr.getTunnelingRequestDataBlock().getSequenceCounter(), Status.NO_ERROR));
                    transaction.submit(() -> context.sendToWire(ack));

                    // Finish the request-transaction.
                    transaction.endRequest();
                }));
        }
        return future;
    }

    @Override
    protected void decode(ConversationContext<KNXNetIPMessage> context, KNXNetIPMessage msg) throws Exception {
        // Handle a normal tunneling request, which is delivering KNX data.
        if(msg instanceof TunnelingRequest) {
            TunnelingRequest tunnelingRequest = (TunnelingRequest) msg;
            final short curCommunicationChannelId =
                tunnelingRequest.getTunnelingRequestDataBlock().getCommunicationChannelId();

            // Only if the communication channel id match, do anything with the request.
            // In case of a passive-mode driver we'll simply accept all communication ids.
            if(knxNetIpDriverContext.isPassiveMode() ||
                (curCommunicationChannelId == knxNetIpDriverContext.getCommunicationChannelId())) {
                // Data packets received from a link layer tunneling connection.
                if(tunnelingRequest.getCemi() instanceof CEMIDataInd) {
                    CEMIDataInd dataInd = (CEMIDataInd) tunnelingRequest.getCemi();
                    final CEMIDataFrame cemiDataFrame = dataInd.getCemiDataFrame();
                    processCemiData(cemiDataFrame.getSourceAddress(), cemiDataFrame.getDestinationAddress(),
                        cemiDataFrame.getDataFirstByte(), cemiDataFrame.getData());
                }
                // Data packets received from a busmonitor tunneling connection.
                else if(tunnelingRequest.getCemi() instanceof CEMIBusmonInd) {
                    CEMIBusmonInd busmonInd = (CEMIBusmonInd) tunnelingRequest.getCemi();
                    if (busmonInd.getCemiFrame() instanceof CEMIFrameData) {
                        CEMIFrameData cemiDataFrame = (CEMIFrameData) busmonInd.getCemiFrame();
                        processCemiData(cemiDataFrame.getSourceAddress(), cemiDataFrame.getDestinationAddress(),
                            cemiDataFrame.getDataFirstByte(), cemiDataFrame.getData());
                    }
                }

                // Confirm receipt of the request.
                final short sequenceCounter = tunnelingRequest.getTunnelingRequestDataBlock().getSequenceCounter();
                TunnelingResponse tunnelingResponse = new TunnelingResponse(
                    new TunnelingResponseDataBlock(knxNetIpDriverContext.getCommunicationChannelId(), sequenceCounter,
                        Status.NO_ERROR));
                context.sendToWire(tunnelingResponse);
            }
        } else if(msg instanceof TunnelingResponse) {
            // This is just handling of all the Ack messages that might come in for any read- or
            // write requests. Usually this is just OK (I haven't managed to fake a message to cause
            // something else than an OK)
        }
    }

    protected void processCemiData(KNXAddress sourceAddress, byte[] destinationGroupAddress,
                                   byte firstByte, byte[] restBytes) throws ParseException {
        // The first byte is actually just 6 bit long, but we'll treat it as a full one.
        // So here we create a byte array containing the first and all the following bytes.
        byte[] payload = new byte[1 + restBytes.length];
        payload[0] = firstByte;
        System.arraycopy(restBytes, 0, payload, 1, restBytes.length);

        // Decode the group address depending on the project settings.
        ReadBuffer addressBuffer = new ReadBuffer(destinationGroupAddress);
        final KNXGroupAddress knxGroupAddress =
            KNXGroupAddressIO.staticParse(addressBuffer, knxNetIpDriverContext.getGroupAddressType());
        final String destinationAddress = toString(knxGroupAddress);

        // If there is an ETS5 model provided, continue decoding the payload.
        if (knxNetIpDriverContext.getEts5Model() != null) {
            final GroupAddress groupAddress =
                knxNetIpDriverContext.getEts5Model().getGroupAddresses().get(destinationAddress);

            if ((groupAddress != null) && (groupAddress.getType() != null)) {
                LOGGER.info(String.format("Message from: '%s' to: '%s'",
                    toString(sourceAddress), destinationAddress));

                // Parse the payload depending on the type of the group-address.
                ReadBuffer rawDataReader = new ReadBuffer(payload);
                final PlcValue value = KnxDatapointIO.staticParse(rawDataReader,
                    groupAddress.getType().getMainType(), groupAddress.getType().getSubType());

                // Assemble the plc4x return data-structure.
                Map<String, PlcValue> dataPointMap = new HashMap<>();
                dataPointMap.put("sourceAddress", new PlcString(toString(sourceAddress)));
                dataPointMap.put("targetAddress", new PlcString(groupAddress.getGroupAddress()));
                if (groupAddress.getFunction() != null) {
                    dataPointMap.put("location", new PlcString(groupAddress.getFunction().getSpaceName()));
                    dataPointMap.put("function", new PlcString(groupAddress.getFunction().getName()));
                } else {
                    dataPointMap.put("location", null);
                    dataPointMap.put("function", null);
                }
                dataPointMap.put("description", new PlcString(groupAddress.getName()));
                dataPointMap.put("unitOfMeasurement", new PlcString(groupAddress.getType().getName()));
                dataPointMap.put("value", value);
                final PlcStruct dataPoint = new PlcStruct(dataPointMap);

                // Send the data-structure.
                publishEvent(groupAddress, dataPoint);
            } else {
                LOGGER.warn(
                    String.format("Message from: '%s' to unknown group address: '%s'%n payload: '%s'",
                        toString(sourceAddress), destinationAddress, Hex.encodeHexString(payload)));
            }
        }
        // Else just output the raw payload.
        else {
            LOGGER.info(String.format("Raw Message: '%s' to: '%s'%n payload: '%s'",
                KnxNetIpProtocolLogic.toString(sourceAddress), destinationAddress,
                Hex.encodeHexString(payload))
            );
        }
    }

    @Override
    public void close(ConversationContext<KNXNetIPMessage> context) {
        // TODO Implement Closing on Protocol Level
    }

    @Override
    public CompletableFuture<PlcSubscriptionResponse> subscribe(PlcSubscriptionRequest subscriptionRequest) {
        Map<String, ResponseItem<PlcSubscriptionHandle>> values = new HashMap<>();
        for (String fieldName : subscriptionRequest.getFieldNames()) {
            final PlcField field = subscriptionRequest.getField(fieldName);
            if(!(field instanceof KnxNetIpField)) {
                values.put(fieldName, new ResponseItem<>(PlcResponseCode.INVALID_ADDRESS, null));
            } else {
                values.put(fieldName, new ResponseItem<>(PlcResponseCode.OK,
                    new KnxNetIpSubscriptionHandle(this, (KnxNetIpField) field)));
            }
        }
        return CompletableFuture.completedFuture(
            new DefaultPlcSubscriptionResponse((InternalPlcSubscriptionRequest) subscriptionRequest, values));
    }

    @Override
    public PlcConsumerRegistration register(Consumer<PlcSubscriptionEvent> consumer, Collection<PlcSubscriptionHandle> collection) {
        final DefaultPlcConsumerRegistration consumerRegistration =
            new DefaultPlcConsumerRegistration(this, consumer, collection.toArray(new InternalPlcSubscriptionHandle[0]));
        consumers.put(consumerRegistration, consumer);
        return consumerRegistration;
    }

    @Override
    public void unregister(PlcConsumerRegistration plcConsumerRegistration) {
        DefaultPlcConsumerRegistration consumerRegistration = (DefaultPlcConsumerRegistration) plcConsumerRegistration;
        consumers.remove(consumerRegistration);
    }

    protected void publishEvent(GroupAddress groupAddress, PlcValue plcValue) {
        // Create a subscription event from the input.
        // TODO: Check this ... this is sort of not really right ...
        final PlcSubscriptionEvent event = new DefaultPlcSubscriptionEvent(Instant.now(),
            Collections.singletonMap("knxData", new ResponseItem<>(PlcResponseCode.OK, plcValue)));

        // Try sending the subscription event to all listeners.
        for (Map.Entry<DefaultPlcConsumerRegistration, Consumer<PlcSubscriptionEvent>> entry : consumers.entrySet()) {
            final DefaultPlcConsumerRegistration registration = entry.getKey();
            final Consumer<PlcSubscriptionEvent> consumer = entry.getValue();
            // Only if the current data point matches the subscription, publish the event to it.
            for (InternalPlcSubscriptionHandle handle : registration.getAssociatedHandles()) {
                if(handle instanceof KnxNetIpSubscriptionHandle) {
                    KnxNetIpSubscriptionHandle subscriptionHandle = (KnxNetIpSubscriptionHandle) handle;
                    // Check if the subscription matches this current event.
                    if (subscriptionHandle.getField().matchesGroupAddress(groupAddress)) {
                        consumer.accept(event);
                    }
                }
            }
        }
    }

    protected byte[] toKnxAddressData(KnxNetIpField field) {
        WriteBuffer address = new WriteBuffer(2);
        try {
            switch (knxNetIpDriverContext.getGroupAddressType()) {
                case 3:
                    address.writeUnsignedShort(5, Short.valueOf(field.getMainGroup()));
                    address.writeUnsignedByte(3, Byte.valueOf(field.getMiddleGroup()));
                    address.writeUnsignedShort(8, Short.valueOf(field.getSubGroup()));
                    break;
                case 2:
                    address.writeUnsignedShort(5, Short.valueOf(field.getMainGroup()));
                    address.writeUnsignedShort(11, Short.valueOf(field.getSubGroup()));
                    break;
                case 1:
                    address.writeUnsignedShort(16, Short.valueOf(field.getSubGroup()));
                    break;
            }
        } catch (Exception e) {
            throw new PlcRuntimeException("Error converting field into knx address data.", e);
        }
        return address.getData();
    }

    protected static String toString(KNXAddress knxAddress) {
        return knxAddress.getMainGroup() + "." + knxAddress.getMiddleGroup() + "." + knxAddress.getSubGroup();
    }

    protected static String toString(KNXGroupAddress groupAddress) {
        if (groupAddress instanceof KNXGroupAddress3Level) {
            KNXGroupAddress3Level level3 = (KNXGroupAddress3Level) groupAddress;
            return level3.getMainGroup() + "/" + level3.getMiddleGroup() + "/" + level3.getSubGroup();
        } else if (groupAddress instanceof KNXGroupAddress2Level) {
            KNXGroupAddress2Level level2 = (KNXGroupAddress2Level) groupAddress;
            return level2.getMainGroup() + "/" + level2.getSubGroup();
        } else if(groupAddress instanceof KNXGroupAddressFreeLevel) {
            KNXGroupAddressFreeLevel free = (KNXGroupAddressFreeLevel) groupAddress;
            return free.getSubGroup() + "";
        }
        throw new PlcRuntimeException("Unsupported Group Address Type " + groupAddress.getClass().getName());
    }

}
