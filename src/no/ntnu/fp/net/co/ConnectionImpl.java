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
	private static final int MAXPORT = 48900;
	private static final int STARTPORT = 5555;

	private int sendTries = 0; //keeps track of how many times we have tried to send a packet and not received answer
	private static final int MAXSENDTRIES = 2;
	private int receiveTries = 0; //keeps track of how many times we have tried to receive a packet and not received answer
	private static final int MAXRECEIVETRIES = 2;

	private KtnDatagram lastPacket = null;
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
		if(ack != null)
			this.remotePort = ack.getSrc_port();
		else
			throw new SocketTimeoutException();

		if(ack.getFlag() == Flag.SYN_ACK){
			//If we received a syn_ack from the right server the connection is established
			state = State.ESTABLISHED;
			//System.out.println("Client Established!");
		}
		//System.out.println(ack.getFlag());
		sendAck(ack,false);
	}
	/**
	 * Listen for, and accept, incoming connections.
	 * 
	 * @return A new ConnectionImpl-object representing the new connection.
	 * @see Connection#accept()
	 */
	public Connection accept() throws IOException, SocketTimeoutException {
		KtnDatagram ack;
		state = State.LISTEN;
		KtnDatagram packet;
		do{
			packet = receivePacket(true);
		}
		while(packet == null || packet.getFlag() != Flag.SYN);
		state = State.SYN_RCVD;
		ConnectionImpl c = new ConnectionImpl(findFreePort());//method to find a free port
		c.remotePort = packet.getSrc_port();
		c.remoteAddress = packet.getSrc_addr();
		try{
			c.sendAck(packet,true);
		} catch (IOException e) {
			Log.writeToLog("sendAck failed", "ConnectionImpl");
			e.printStackTrace();
		}
		ack = c.receiveAck();
		if(!ack.getSrc_addr().equals(c.remoteAddress)){
			System.out.println("ACK SRC not Equal to remoteAdress");
			System.out.println("Ack.src: "+ ack.getSrc_addr());
			System.out.println("Remoteadress: " + c.remoteAddress);
			
			return null;
		}
		if(ack == null || ack.getFlag() != Flag.ACK)
			throw new SocketTimeoutException();
		
		c.state = State.ESTABLISHED;
		//System.out.println("Server connection up");
		Log.writeToLog("Connection established", "Client");
		state = State.LISTEN;
		return (Connection)c;
	}
	/**
	 * Finds a free port for the accept method 
	 * Alltough originaly it was based on the idea of usedPorts holding available ports (which would be far more elegant), 
	 * the method now generates more input for the usedPorts and eventually throws an exception if the port number get to high.
	 * 
	 * @return
	 * @throws IOException
	 */
	public int findFreePort() throws IOException{//method to find a free port
		int port = STARTPORT;
		usedPorts.put(port, false);
		boolean foundPort = false;
		while(!foundPort){
			port = (int) (Math.random()*MAXPORT-STARTPORT)+STARTPORT;
			try{
				if(!usedPorts.get(port)){ //assuming usedport is stored as false, this if should hit when we find a freeport
					foundPort = true;
					usedPorts.put(port, false);
				}
			}
			catch(NullPointerException e){//There is no previously used port that is free in usedPorts, so we need to add a new one 
				if(port < MAXPORT){
					usedPorts.put(port, false);
					port = STARTPORT;
				}
			}
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
		if(state != State.ESTABLISHED)
			throw new ConnectException("Tried to send while the state is not established");
		KtnDatagram packet = constructDataPacket(msg);
		KtnDatagram ack = sendDataPacketWithRetransmit(packet);
		if(ack != null){ //we got an ack!
			System.out.println("through null test");
			if(!isValid(ack) || ack.getAck() > nextSequenceNo-1){ //if the ack we received is not valid or if the ack number is too high we are dealing with a ghost package
				System.out.println("failed valid or seqNrTest: 1. valid test, 2. ackSeqNr too high");
				System.out.println(!isValid(ack));
				System.out.println(ack.getSeq_nr() >= nextSequenceNo);
				sendTries++;//treating ghost (ack)package as if we did not receive ack from other side
				send(msg);
				sendTries = 0;
				return;
			}
			else if(ack.getAck() < nextSequenceNo-1){ //we received an ack for the last package, resending this one
				System.out.println("received ack for last package");
				send(msg);
				return;
			}
			else{//we got valid ACK
				System.out.println("Valid ACK");
			}
		}
		else{//did not get ack from the other side
			if(sendTries < MAXSENDTRIES){
				sendTries++;
				send(msg); //tries to send message again
				sendTries = 0;
				return;
			}
			else{//we consider the connection lost
				state = State.CLOSED; //TODO how to handle cut connection
				throw new ConnectException("Connection lost "); //not sure how to handle lost connection
			}
		}
	}

	/**
	 * Wait for incoming data.
	 * 
	 * @return The received data's payload as a String.
	 * @see Connection#receive()
	 * @see AbstractConnection#receivePacket(boolean)
	 * @see AbstractConnection#sendAck(KtnDatagram, boolean)
	 */
	public String receive() throws ConnectException, IOException, EOFException {
		KtnDatagram packet = null;
		try{
			packet = receivePacket(false);			
			System.out.println(packet);
		}
		catch(EOFException e){ // EOFException means that we got a FIN
			//lastPacket = packet;
			state = State.CLOSE_WAIT;
			throw new EOFException();
		}
		System.out.println("RECEIVED PACKET! true if we got packet:");
		System.out.println(packet != null);
		if(packet == null){ // timed out tries again according to MaxreceiveTries
			if(receiveTries < MAXRECEIVETRIES){
				receiveTries++;
				String msg = receive();
				receiveTries=0;
				return msg;
			}
			else{
				state = State.CLOSED;//TODO check if additional operations is needed to close connection
				throw new ConnectException("Connection Lost");
			}
		}
		else{ //received a packet
			if(packet.getSrc_addr() != null && packet.getSrc_port() == remotePort && packet.getSrc_addr().equals(remoteAddress)){//true if we are not dealing with a ghost packet
				System.out.println("Through ghost check");
				if(!isValid(packet)){
					System.out.println("Failed valid check");
					if(lastPacket != null){ 	//lastPacket is only null when the first packet is sent,
						//in the case that the first packet is null we will remain silent and pretend we did not get it.
						sendAck(lastPacket, false);
						return receive();
					}
					return receive();
				}
				else{//packet has valid checksum
					System.out.println("Though Valid Check");
					if(lastPacket != null && packet.getSeq_nr()!=lastPacket.getSeq_nr()+1){
						System.out.println("Failed packet null check or seqNr check, true is bad: 1. null check, 2. seqNr check");
						System.out.println(lastPacket != null);
						System.out.println(packet.getSeq_nr()!=lastPacket.getSeq_nr()-1);
						sendAck(lastPacket, false);
						return receive();
					}else{//Valid Packet
						System.out.println("Valid packet!");
						sendAck(packet,false);
						lastPacket = packet;
						System.out.println("Last Packet (from receive): ");
						System.out.println(lastPacket);
						return (String) packet.getPayload();
				}}
			}
			else{//we received a ghost package
				System.out.println("failed ghost check: 1.true if packetsource != null, 2. true if correct remoteport, 3. true if correct remoteAddress");
				System.out.println(packet.getSrc_addr() != null);
				System.out.println(packet.getSrc_port() == remotePort);
				System.out.println(packet.getSrc_addr().equals(remoteAddress));
				return receive();
			}
		}
		//return "";
		//remember timeout and then call send
	}

	/**
	 * Close the connection.
	 * @see Connection#close()
	 */
	public void close() throws IOException {
		System.out.println("System in state : " + state);
		KtnDatagram ack = null;
		KtnDatagram packet = null;
		KtnDatagram finack = null;
		if(state == State.CLOSE_WAIT){
			System.out.println("Last Packet: "+lastPacket);
			sendAck(lastPacket, false);
			packet = constructInternalPacket(Flag.FIN);

			try {
				Thread.currentThread().sleep(100);//Wait for client to be ready to recieve FIN
				simplySendPacket(packet);
			} catch (ClException e) {
				e.printStackTrace();
			}
			catch (InterruptedException e1) {
				e1.printStackTrace();
			}
			ack = receiveAck();
			if(ack == null)
				//How to handle this? Answer: Do nothing!
			state = State.CLOSED;
		}
		else if(state == State.ESTABLISHED){
			packet = constructInternalPacket(Flag.FIN);
			try {
				simplySendPacket(packet);
			} catch (ClException e) {
				e.printStackTrace();
			}
			state = State.FIN_WAIT_1;
			ack = receiveAck();
			if(ack == null){
				if(sendTries < MAXSENDTRIES){
					state = State.ESTABLISHED;
					close();
					return;					
				}
				else{
					state = State.CLOSED;
				}
			}
			state = State.FIN_WAIT_2;
			
			System.out.println("Waiting for Fin2");
			finack = receiveAck();
			if(finack == null)
				finack = receiveAck();
			System.out.println("Done waiting for Fin2");
			if(finack != null)
				sendAck(finack, false);
				state = State.TIME_WAIT;
			
			state = State.CLOSED;
			
		}
		else{
			System.out.println("Impressive you managed to call close in the state: " + state);
			throw new IOException();
		}
		
		
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
		if(packet.getChecksum() == packet.calculateChecksum())
			return true;
		return false;
	}
}
