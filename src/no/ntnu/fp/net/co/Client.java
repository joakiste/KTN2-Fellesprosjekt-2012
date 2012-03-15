package no.ntnu.fp.net.co;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;

public class Client extends AbstractConnection{

	@Override
	public void connect(InetAddress remoteAddress, int remotePort)
			throws IOException, SocketTimeoutException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Connection accept() throws IOException, SocketTimeoutException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void send(String msg) throws ConnectException, IOException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public String receive() throws ConnectException, IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void close() throws IOException {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected boolean isValid(KtnDatagram packet) {
		// TODO Auto-generated method stub
		return false;
	}
	public static void main(String args[]){
		Client c = new Client();
		Flag flag = Flag.FIN;
		
		KtnDatagram packet = c.constructInternalPacket(flag);
		System.out.println(packet.getChecksum());
	}
}
