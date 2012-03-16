/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;

import javax.sound.sampled.ReverbType;

//import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import no.ntnu.fp.net.admin.Log;
import no.ntnu.fp.net.cl.ClException;
import no.ntnu.fp.net.cl.ClSocket;
import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;
import no.ntnu.fp.net.co.AbstractConnection.State;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behaviour in the methods specified in the interface
 * {@link Connection} over the unreliable, connectionless network realised in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 * @author Sebjørn Birkeland and Stein Jakob Nordbø
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {
	
    /** Keeps track of the used ports for each server port. */
    private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());

    /**
     * Initialise initial sequence number and setup state machine.
     * 
     * @param myPort
     *            - the local port to associate with this connection
     */
    public ConnectionImpl(int myPort) {
        this.myPort = myPort;
    	this.myAddress = getIPv4Address();
    	//throw new NotImplementedException();
    }

    private String getIPv4Address(){
        try {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    /**
     * Establish a connection to a remote location.
     * 
     * @param remoteAddress
     *            - the remote IP-address to connect to
     * @param remotePort
     *            - the remote portnumber to connect to
     * @throws IOException
     *             If there's an I/O error.
     * @throws java.net.SocketTimeoutException
     *             If timeout expires before connection is completed.
     * @see Connection#connect(InetAddress, int)
     * @see AbstractConnection#receiveAck()
     */
    public void connect(InetAddress remoteAddress, int remotePort) throws IOException, SocketTimeoutException{
    	KtnDatagram ack;
    	this.remoteAddress = remoteAddress.getHostAddress();
    	this.remotePort = remotePort;
    	KtnDatagram IPacket = constructInternalPacket(Flag.SYN);
    	// uses a self made method similar to sendDataPacketWithRetransmit() because we need to send a packet even though the state is set to CLOSED
    	//ack = sendPacketWithRetransmitConnect(IPacket);
    	try{
			simplySendPacket(IPacket);
		} catch (ClException e) {
			Log.writeToLog("SimplySendFailed", "ConnectionImpl");
			e.printStackTrace();
		}
    	state = State.SYN_SENT;
    	ack = receiveAck();
    	if(ack == null){
    		//seems like the receiveAck() returns null if it timed out. Link in javadoc 
    		throw new SocketTimeoutException();
    	}
    	remotePort = ack.getSrc_port();
    	if(ack.getFlag() == Flag.SYN_ACK && ack.getSrc_addr() == this.remoteAddress){
    		//If we received a syn_ack from the right server the connection is established
    		state = State.ESTABLISHED;
    		System.out.println("Client Established!");
    	}
    	else{
    		//Handle case: ghost packet occured
    		state = State.CLOSED;
    		//starts a new connect and thus disregards the ghost packet
    		connect(remoteAddress,remotePort);
    		return;
    	}
    	IPacket = constructInternalPacket(Flag.ACK);
    	sendDataPacketWithRetransmit(IPacket);
    }
    /**
     * Improved the simplysend method, but only for the connectMethod due to state change in the method
     */
    private KtnDatagram sendPacketWithRetransmitConnect(KtnDatagram packet) throws EOFException, IOException{
    	lastDataPacketSent = packet;
    	
    	// Create a timer that sends the packet and retransmits every
    	// RETRANSMIT milliseconds until cancelled.
    	// similar algorithm to the one found in sendDataPacketWithRetransmit()
    	Timer timer = new Timer();
    	timer.scheduleAtFixedRate(new SendTimer(new ClSocket(), packet), 0, RETRANSMIT);
    	state = State.SYN_SENT; //state change
    	KtnDatagram ack = receiveAck();
    	timer.cancel();
    	return ack; //returns the ack from the server
    }

    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
    	state = State.LISTEN;
    	KtnDatagram packet;
    	do{
    		packet = receivePacket(true);
    	}
    	while(packet == null || packet.getFlag() != Flag.SYN);
    	state = State.SYN_RCVD;
    	ConnectionImpl c = new ConnectionImpl(findFreePort());//method to find a free port
    	c.remotePort = packet.getDest_port();
    	c.remoteAddress = packet.getSrc_addr();
    	packet = c.constructInternalPacket(Flag.SYN_ACK);
    	try{
			simplySendPacket(packet);
		} catch (ClException e) {
			Log.writeToLog("SimplySendFailed", "ConnectionImpl");
			e.printStackTrace();
		}
    	packet = c.receiveAck();
    	c.state = State.ESTABLISHED;
		System.out.println("Server Established!");
    	state = State.LISTEN;
    	return (Connection)c;
    	}
    
    public int findFreePort() throws IOException{//method to find a free port
    	int port = 5555;
    	int startPort = port;
    	boolean foundPort = false;
    	int numPorts = usedPorts.size();
    	while(!foundPort){
    		port++;
    		port = port % numPorts;
    		if(!usedPorts.get(port)){ //assuming usedport is stored as false, this if should hit when we find a freeport
    			foundPort = true; 
    		}
    		if(port == startPort) //so it has come to this, after one ciculation this if will hit and there is no freeports
    			//Log.writeToLog("No free ports", "AbstractConnection");
    			throw new IOException();
    	}
    	return port;
    }

    /**
     * Send a message from the application.
     * 
     * @param msg
     *            - the String to be sent.
     * @throws ConnectException
     *             If no connection exists
     * @throws IOException
     *             If no ACK was received.
     * @see AbstractConnection#sendDataPacketWithRetransmit(KtnDatagram)
     * @see no.ntnu.fp.net.co.Connection#send(String)
     */
    public void send(String msg) throws ConnectException, IOException {
        throw new NotImplementedException();
    }

    /**
     * Wait for incoming data.
     * 
     * @return The received data's payload as a String.
     * @see Connection#receive()
     * @see AbstractConnection#receivePacket(boolean)
     * @see AbstractConnection#sendAck(KtnDatagram, boolean)
     */
    public String receive() throws ConnectException, IOException {
        throw new NotImplementedException();
        //remember timeout and then call send
    }

    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
        throw new NotImplementedException();
    }

    /**
     * Test a packet for transmission errors. This function should only called
     * with data or ACK packets in the ESTABLISHED state.
     * 
     * @param packet
     *            Packet to test.
     * @return true if packet is free of errors, false otherwise.
     */
    protected boolean isValid(KtnDatagram packet) {
    	packet.setChecksum(checksum);
        throw new NotImplementedException();
    }
}
