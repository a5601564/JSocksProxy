/**
 * JSocksProxy Copyright (c) 2006-2017 Kenny Colliander Nordin
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nu.najt.kecon.jsocksproxy.socks5;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nu.najt.kecon.jsocksproxy.AbstractSocksImplementation;
import nu.najt.kecon.jsocksproxy.ConfigurationFacade;
import nu.najt.kecon.jsocksproxy.IllegalAddressTypeException;
import nu.najt.kecon.jsocksproxy.IllegalCommandException;
import nu.najt.kecon.jsocksproxy.ProtocolException;

/**
 * This is the SOCKS5 implementation.<br>
 * <br>
 * More about the SOCKS protocol
 * <a href="http://en.wikipedia.org/wiki/SOCKS">http
 * ://en.wikipedia.org/wiki/SOCKS</a>
 * 
 * @author Kenny Colliander Nordin
 * 
 */
public class SocksImplementation5 extends AbstractSocksImplementation {

	private static final byte PROTOCOL_VERSION = 0x05;

	private static final Logger LOG = LoggerFactory
			.getLogger(SocksImplementation5.class.getPackage().getName());

	/**
	 * Constructor
	 * 
	 * @param configurationFacade
	 *            the configuration facade
	 * @param clientSocket
	 *            the client socket
	 * @param executor
	 *            the executor
	 */
	public SocksImplementation5(final ConfigurationFacade configurationFacade,
			final Socket clientSocket, final Executor executor) {
		super(configurationFacade, clientSocket, SocksImplementation5.LOG,
				executor);
	}

	@Override
	public void run() {
		DataInputStream inputStream = null;
		DataOutputStream outputStream = null;
		Socket clientSocket = null;
		AddressType addressType = AddressType.IP_V4;
		String host = null;

		try {
			this.setup();

			// Handshake
			inputStream = new DataInputStream(this.getClientInputStream());
			outputStream = new DataOutputStream(
					new BufferedOutputStream(this.getClientOutputStream()));

			this.authenticate(inputStream, outputStream);

			readVersion(inputStream);

			final Command command = Command.valueOf(inputStream.readByte());
			final byte[] hostname;

			inputStream.readByte(); // reserved byte

			addressType = AddressType.valueOf(inputStream.readByte());
			final InetAddress remoteInetAddress;
			if (addressType == AddressType.IP_V4) {

				final byte[] address = new byte[4];
				inputStream.readFully(address);
				remoteInetAddress = InetAddress.getByAddress(address);
				hostname = null;

			} else if (addressType == AddressType.IP_V6) {

				final byte[] address = new byte[16];
				inputStream.readFully(address);
				remoteInetAddress = InetAddress.getByAddress(address);
				hostname = null;

			} else if (addressType == AddressType.DOMAIN) {

				final int hostLength = inputStream.readByte() & 0xFF;
				final byte[] hostBuf = new byte[hostLength];

				inputStream.readFully(hostBuf);
				remoteInetAddress = InetAddress
						.getByName(new String(hostBuf, "US-ASCII"));
				hostname = hostBuf;

			} else {
				// Should be impossible
				throw new IllegalAddressTypeException(
						"Unsupported address type: " + addressType);
			}

			host = remoteInetAddress.getHostAddress();
			final int port = inputStream.readShort() & 0xFFFF;

			if (command == Command.CONNECT) {
				try {
					clientSocket = this.openConnection(remoteInetAddress,
							port);
				} catch (final IOException e) {
					this.logger.info("Failed to connect to: {}:{}", host,
							port);
					this.writeResponse(outputStream, Status.HOST_UNREACHABLE,
							addressType, remoteInetAddress, hostname, port);
					return;
				}

				this.writeResponse(outputStream, Status.SUCCEEDED, addressType,
						clientSocket.getLocalAddress(), hostname,
						clientSocket.getLocalPort());

				this.tunnel(this.getClientSocket(), clientSocket);

				this.logger.debug("Disconnected");
			} else if (command == Command.BIND) {
				final ServerSocket serverSocket = this
						.bindConnection(remoteInetAddress, port);

				try {

					this.writeResponse(outputStream, Status.SUCCEEDED,
							addressType, serverSocket.getInetAddress(),
							hostname, serverSocket.getLocalPort());

					clientSocket = serverSocket.accept();
					this.logger.info("Accepted");
				} finally {
					serverSocket.close();
				}

				this.writeResponse(outputStream, Status.SUCCEEDED, addressType,
						clientSocket.getInetAddress(), null,
						clientSocket.getPort());

				this.tunnel(this.getClientSocket(), clientSocket);
			} else {
				throw new IllegalCommandException(
						"Unknown command: " + command);
			}
		} catch (final UnknownHostException e) {
			this.logger.warn("Failed to resolve host: {}", host, e);
			try {
				this.writeResponse(outputStream, Status.HOST_UNREACHABLE,
						addressType, null, null, 0);
			} catch (final IOException ioe) {
			}
		} catch (final RuntimeException e) {
			this.logger.warn("Unknown error occurred", e);

			try {
				this.writeResponse(outputStream,
						Status.GENERAL_SOCKS_SERVER_FAILURE, addressType, null,
						null, 0);
			} catch (final IOException ioe) {
			}
		} catch (final ProtocolException | IllegalCommandException e) {
			try {
				this.writeResponse(outputStream, Status.COMMAND_NOT_SUPPORTED,
						addressType, null, null, 0);
			} catch (final IOException ioe) {
			}
		} catch (final IOException e) {
		} catch (final IllegalAddressTypeException e) {
			try {
				this.writeResponse(outputStream,
						Status.ADDRESS_TYPE_NOT_SUPPORTED, addressType, null,
						null, 0);
			} catch (final IOException ioe) {
			}
		} finally {
			if (inputStream != null) {
				try {
					inputStream.close();
				} catch (final IOException e) {
				}
			}

			if (outputStream != null) {
				try {
					outputStream.close();
				} catch (final IOException e) {
				}
			}

			if (this.getClientSocket() != null) {
				try {
					this.getClientSocket().close();
				} catch (final IOException e) {
				}
			}

			if (clientSocket != null) {
				try {
					clientSocket.close();
				} catch (final IOException e) {
				}
			}

			this.cleanup();
		}
	}

	private void readVersion(DataInputStream inputStream)
			throws IOException, ProtocolException {
		final byte socksVersion = inputStream.readByte();

		if (socksVersion != SocksImplementation5.PROTOCOL_VERSION) {
			throw new ProtocolException("Unsupported version: 0x"
					+ Integer.toHexString(socksVersion));
		}
	}

	protected void authenticate(final DataInputStream inputStream,
			final DataOutputStream outputStream) throws IOException {
		final int numberOfAuthMethods = inputStream.readByte() & 0xFF;

		boolean supported = false;
		for (int i = 0; i < numberOfAuthMethods; i++) {
			final byte authMethod = inputStream.readByte();

			if (authMethod == 0x00) {
				supported = true;
			}
		}

		final byte handshakeResponse[] = new byte[2];
		handshakeResponse[0] = SocksImplementation5.PROTOCOL_VERSION;

		if (supported) {
			handshakeResponse[1] = 0x00;
			outputStream.write(handshakeResponse);
			outputStream.flush();
		} else {
			handshakeResponse[1] = (byte) 0xff;
			outputStream.write(handshakeResponse);
			outputStream.flush();
			this.logger.info("No supported authentication methods specified");
			throw new EOFException();
		}
	}

	/**
	 * Write response back to client
	 * 
	 * @param outputStream
	 *            the output stream
	 * @param status
	 *            the status
	 * @param addressType
	 *            the address type
	 * @param boundAddress
	 *            the bound address
	 * @param hostname
	 *            the hostname
	 * @param port
	 *            the port
	 * @throws IOException
	 *             if an I/O exception occurs
	 */
	protected void writeResponse(final DataOutputStream outputStream,
			final Status status, final AddressType addressType,
			final InetAddress boundAddress, final byte[] hostname,
			final int port) throws IOException {

		byte[] safeAddress = null;
		if (boundAddress == null) {
			if (addressType == AddressType.IP_V6) {
				safeAddress = new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
						0, 0, 0, 0 };
			} else {
				safeAddress = new byte[] { 0, 0, 0, 0 };
			}
		} else if (hostname == null) {
			safeAddress = boundAddress.getAddress();
		} else {
			safeAddress = hostname;
		}

		if (status != Status.SUCCEEDED) {
			this.logger.info("Client failed to connect, result 0x{} {}",
					Integer.toHexString(status.getValue()), status);
		}

		outputStream.write(SocksImplementation5.PROTOCOL_VERSION);
		outputStream.write(status.getValue());
		outputStream.write(0x00); // reserved

		if (addressType == AddressType.IP_V4) {
			outputStream.write(AddressType.IP_V4.getValue());
			outputStream.write(safeAddress);

		} else if (addressType == AddressType.IP_V6) {
			outputStream.write(AddressType.IP_V6.getValue());
			outputStream.write(safeAddress);

		} else if (addressType == AddressType.DOMAIN) {
			outputStream.write(AddressType.DOMAIN.getValue());
			if (safeAddress == null) {
				outputStream.write(0x00);
			} else {
				outputStream.write(safeAddress.length);
				outputStream.write(safeAddress);
			}
		} else {
			throw new IllegalStateException(
					"Unknown address type: " + addressType);
		}

		outputStream.writeShort(port & 0xFFFF);
		outputStream.flush();
	}
}
