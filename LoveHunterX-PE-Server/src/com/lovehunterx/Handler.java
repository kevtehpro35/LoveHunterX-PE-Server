package com.lovehunterx;

import java.net.InetSocketAddress;

import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;

public class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
	private ChannelHandlerContext ctx;
	private InetSocketAddress sender;

	public static DatagramPacket createDatagramPacket(Packet p, InetSocketAddress addr) {
		return new DatagramPacket(Unpooled.copiedBuffer(p.toJSON(), CharsetUtil.UTF_8), addr);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
		this.ctx = ctx;
		this.sender = packet.sender();

		String message = packet.content().toString(CharsetUtil.US_ASCII);
		try {
			interpretInput(ctx, message);
		} catch (ParseException e) {
		}
	}

	private void interpretInput(ChannelHandlerContext ctx, String message) throws ParseException {
		JSONParser parser = new JSONParser();
		JSONObject obj = (JSONObject) parser.parse(message);
		Packet p = new Packet(obj);
		switch (p.getAction()) {
		case "auth":
			handleAuthentication(p);
			break;
		case "reg":
			handleRegistration(p);
			break;
		case "join":
			handleJoin(p);
			break;
		case "move":
			handleMovement(p);
			break;
		case "disconnect":
			handleLeave();
			break;
		}
	}

	private void handleRegistration(Packet p) {
		boolean success = Server.db.register(p.getData("user"), p.getData("pass"));
		Packet regPacket = Packet.createRegPacket(success);
		ctx.writeAndFlush(createDatagramPacket(regPacket, sender));
	}

	private void handleAuthentication(Packet p) {
		boolean success = Server.db.authenticate(p.getData("user"), p.getData("pass"))
				&& !Server.getState().isLoggedIn(p.getData("user"));

		Packet authPacket = Packet.createAuthPacket(success);
		ctx.writeAndFlush(createDatagramPacket(authPacket, sender));

		if (success) {
			Client cli = new Client(sender);
			cli.setUsername(p.getData("user"));

			Server.getState().addClient(cli);
		}
	}

	private void handleJoin(Packet p) {
		Client c = Server.getState().getClient(sender);
		if (c == null) {
			return;
		}

		String room = p.getData("room");
		c.joinRoom(room);
		
		Packet update = Packet.createJoinPacket(c.getUsername(), room, 0, 0);
		for (Client other : Server.getState().getClients()) {
			if (!other.isInRoom(room)) {
				continue;
			}

			p.addData("user", other.getUsername());
			p.addData("x", String.valueOf(other.getX()));
			p.addData("y", String.valueOf(other.getY()));
			p.addData("vel_x", String.valueOf(other.getVelocityX()));
			p.addData("vel_y", String.valueOf(other.getVelocityY()));
			ctx.writeAndFlush(createDatagramPacket(p, sender));

			if (!other.getUsername().equals(c.getUsername())) {
				ctx.writeAndFlush(createDatagramPacket(update, other.getAddress()));
			}
		}
	}

	private void handleMovement(Packet p) {
		Client c = Server.getState().getClient(sender);
		if (c == null) {
			return;
		}
		
		float velX = p.getFloat("vel_x");
		float velY = p.getFloat("vel_y");
		
		if (c.getY() == 0 && velY > 0.30) {
			c.setVelocityY(0.8F);
			c.setVelocityX(3F * velX);
		} else {
			c.setVelocityX(velX);
		}
	}

	public void handleLeave() {
		Client c = Server.getState().getClient(sender);
		if (c == null) {
			return;
		}

		Server.getState().removeClient(sender);

		Packet leave = Packet.createLeavePacket(c.getUsername(), c.getRoom());
		for (Client other : Server.getState().getClients()) {
			if (!other.isInRoom(c.getRoom())) {
				continue;
			}

			ctx.writeAndFlush(createDatagramPacket(leave, other.getAddress()));
		}
	}
}
