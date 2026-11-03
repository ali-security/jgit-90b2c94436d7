/*
 * Copyright (C) 2025, Eclipse Foundation, Inc. and others
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.transport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.Test;

public class AmazonS3Test {

	private static final String SECRET = "injected-secret-content";

	@Test
	public void testListParsesKeys() throws Exception {
		StringBuilder xmlContent = new StringBuilder();
		xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
				.append("<Name>bucket</Name>")
				.append("<Prefix>dir/</Prefix>")
				.append("<IsTruncated>false</IsTruncated>")
				.append("<Contents><Key>dir/a</Key></Contents>")
				.append("<Contents><Key>dir/b/c</Key></Contents>")
				.append("</ListBucketResult>");

		try (FakeS3Server server = new FakeS3Server(
				xmlContent.toString().getBytes(UTF_8))) {
			AmazonS3 s3 = newClient(server.getPort());
			assertEquals(Arrays.asList("a", "b/c"), s3.list("bucket", "dir"));
		}
	}

	@Test
	public void testListRejectsXXE() throws Exception {
		File externalEntity = File.createTempFile("injected", "xml");
		externalEntity.deleteOnExit();
		Files.write(externalEntity.toPath(), SECRET.getBytes(UTF_8),
				StandardOpenOption.WRITE);
		StringBuilder xmlContent = new StringBuilder();
		xmlContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<!DOCTYPE booo [ <!ENTITY foobar SYSTEM \"")
				.append(externalEntity.toURI()).append("\"> ]>\n")
				.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
				.append("<Name>bucket</Name>")
				.append("<Prefix></Prefix>")
				.append("<IsTruncated>false</IsTruncated>")
				.append("<Contents><Key>&foobar;</Key></Contents>")
				.append("</ListBucketResult>");

		try (FakeS3Server server = new FakeS3Server(
				xmlContent.toString().getBytes(UTF_8))) {
			AmazonS3 s3 = newClient(server.getPort());
			try {
				List<String> keys = s3.list("bucket", "");
				fail("AmazonS3 did not reject DOCTYPE declaration, got keys "
						+ keys);
			} catch (IOException e) {
				assertTrue(e.getCause().getMessage().contains("DOCTYPE"));
				assertFalse(String.valueOf(e.getMessage()).contains(SECRET));
			}
		}
	}

	private static AmazonS3 newClient(int port) {
		Properties props = new Properties();
		props.setProperty(AmazonS3.Keys.ACCESS_KEY, "access");
		props.setProperty(AmazonS3.Keys.SECRET_KEY, "secret");
		props.setProperty(AmazonS3.Keys.DOMAIN, "s3.invalid");
		props.setProperty(AmazonS3.Keys.HTTP_RETRY, "1");
		// AmazonS3 captures the default ProxySelector on construction; route
		// all of its requests through the local fake server.
		final List<Proxy> proxies = Collections.singletonList(new Proxy(
				Proxy.Type.HTTP,
				new InetSocketAddress(InetAddress.getLoopbackAddress(), port)));
		ProxySelector original = ProxySelector.getDefault();
		ProxySelector.setDefault(new ProxySelector() {
			@Override
			public List<Proxy> select(URI uri) {
				return proxies;
			}

			@Override
			public void connectFailed(URI uri, SocketAddress sa,
					IOException ioe) {
				// nothing to do
			}
		});
		try {
			return new AmazonS3(props);
		} finally {
			ProxySelector.setDefault(original);
		}
	}

	private static class FakeS3Server implements Runnable, AutoCloseable {
		private final ServerSocket serverSocket;

		private final byte[] body;

		FakeS3Server(byte[] body) throws IOException {
			this.body = body;
			serverSocket = new ServerSocket(0, 50,
					InetAddress.getLoopbackAddress());
			Thread thread = new Thread(this, "FakeS3Server");
			thread.setDaemon(true);
			thread.start();
		}

		int getPort() {
			return serverSocket.getLocalPort();
		}

		@Override
		public void run() {
			while (!serverSocket.isClosed()) {
				try (Socket socket = serverSocket.accept()) {
					BufferedReader in = new BufferedReader(
							new InputStreamReader(socket.getInputStream(),
									UTF_8));
					String line = in.readLine();
					while (line != null && !line.isEmpty()) {
						line = in.readLine();
					}
					OutputStream out = socket.getOutputStream();
					out.write(("HTTP/1.1 200 OK\r\n"
							+ "Content-Type: application/xml\r\n"
							+ "Content-Length: " + body.length + "\r\n"
							+ "Connection: close\r\n\r\n").getBytes(UTF_8));
					out.write(body);
					out.flush();
				} catch (IOException e) {
					// server socket closed or client disconnected
				}
			}
		}

		@Override
		public void close() throws IOException {
			serverSocket.close();
		}
	}
}
