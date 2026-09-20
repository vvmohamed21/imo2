/**
 * WatchRoom Synchronized Watch Party Real-Time Server
 * Built with Express and Socket.io
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());
const path = require('path');
app.use(express.static(path.join(__dirname, 'public')));

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  },
  pingTimeout: 60000,
  pingInterval: 25000
});

const PORT = process.env.PORT || 3000;

// In-Memory Room Data Store
// roomId -> {
//   hostSocketId: string,
//   media: { mediaUrl: string, title: string, isPlaying: boolean, currentTime: number, updatedAt: number },
//   messages: Array<{ id: string, senderId: string, senderName: string, avatar: string, text: string, timestamp: number, isHost: boolean }>,
//   users: Map<socketId, { socketId: string, userName: string, avatar: string, isHost: boolean, isMuted: boolean, joinedAt: number }>
// }
const rooms = new Map();

function getOrCreateRoom(roomId) {
  if (!rooms.has(roomId)) {
    rooms.set(roomId, {
      hostSocketId: null,
      media: {
        mediaUrl: '',
        title: '',
        isPlaying: false,
        currentTime: 0,
        updatedAt: Date.now()
      },
      messages: [],
      users: new Map()
    });
  }
  return rooms.get(roomId);
}

function getSanitizedUsers(room) {
  return Array.from(room.users.values()).map(u => ({
    socketId: u.socketId,
    userName: u.userName,
    avatar: u.avatar,
    isHost: u.isHost,
    isMuted: u.isMuted
  }));
}

// REST Endpoints
app.get('/', (req, res) => {
  res.json({
    app: 'WatchRoom Real-Time Server',
    status: 'online',
    timestamp: Date.now(),
    activeRooms: rooms.size
  });
});

app.get('/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime() });
});

// Direct APK Download Endpoint
app.get('/download', (req, res) => {
  const apkPath = path.join(__dirname, 'public', 'watchroom.apk');
  res.download(apkPath, 'watchroom.apk');
});

// In-App Self Update Endpoint
app.get('/api/version', (req, res) => {
  const host = req.get('host');
  const protocol = req.protocol === 'https' || req.get('x-forwarded-proto') === 'https' ? 'https' : 'http';
  res.json({
    versionCode: 2,
    versionName: "1.0.1",
    apkUrl: `${protocol}://${host}/download`,
    changelog: "Added full cloud server support, Render hosting config, and in-app self updater.",
    forceUpdate: false
  });
});

app.get('/rooms/:roomId', (req, res) => {
  const { roomId } = req.params;
  if (!rooms.has(roomId)) {
    return res.status(404).json({ error: 'Room not found' });
  }
  const room = rooms.get(roomId);
  res.json({
    roomId,
    userCount: room.users.size,
    media: room.media,
    messageCount: room.messages.length,
    users: getSanitizedUsers(room)
  });
});

// Socket.io Real-Time Synchronization Engine
io.on('connection', (socket) => {
  let currentRoomId = null;
  let currentUser = null;

  console.log(`[Socket Connected] Client: ${socket.id}`);

  // 1. Join Room
  socket.on('join_room', (data) => {
    try {
      const { roomId, userName = 'Guest', isHost = false, avatar = '' } = data || {};
      if (!roomId) {
        return socket.emit('error', { message: 'roomId is required' });
      }

      currentRoomId = roomId;
      socket.join(roomId);

      const room = getOrCreateRoom(roomId);

      // Determine host status: either explicitly requested or first participant
      const shouldBeHost = isHost || room.users.size === 0 || room.hostSocketId === null;
      if (shouldBeHost && !room.hostSocketId) {
        room.hostSocketId = socket.id;
      }

      currentUser = {
        socketId: socket.id,
        userName,
        avatar: avatar || `https://api.dicebear.com/7.x/bottts/svg?seed=${encodeURIComponent(userName)}`,
        isHost: room.hostSocketId === socket.id,
        isMuted: false,
        joinedAt: Date.now()
      };

      room.users.set(socket.id, currentUser);

      // Send initial state snapshot to newly joined user
      // Compute estimated current playback position if playing
      let currentPosition = room.media.currentTime;
      if (room.media.isPlaying && room.media.updatedAt) {
        const elapsedSeconds = (Date.now() - room.media.updatedAt) / 1000;
        currentPosition = Math.max(0, currentPosition + elapsedSeconds);
      }

      socket.emit('room:joined', {
        roomId,
        isHost: currentUser.isHost,
        user: currentUser,
        media: {
          ...room.media,
          currentTime: currentPosition
        },
        messages: room.messages.slice(-50), // Last 50 messages for quick sync
        users: getSanitizedUsers(room)
      });

      // Notify others in room
      io.to(roomId).emit('room:users_updated', {
        count: room.users.size,
        users: getSanitizedUsers(room)
      });

      // Broadcast system notice
      const joinNotice = {
        id: `sys-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`,
        senderId: 'system',
        senderName: 'WatchRoom',
        avatar: '',
        text: `${currentUser.userName} joined the room`,
        timestamp: Date.now(),
        isSystem: true
      };
      room.messages.push(joinNotice);
      io.to(roomId).emit('chat:message', joinNotice);

      console.log(`[Room ${roomId}] ${currentUser.userName} joined as ${currentUser.isHost ? 'HOST' : 'GUEST'}. (Users: ${room.users.size})`);
    } catch (err) {
      console.error('Error handling join_room:', err);
    }
  });

  // 2. Media Load Broadcast (Host sniffs/selects direct stream URL)
  socket.on('media:load', (data) => {
    try {
      if (!currentRoomId) return;
      const room = rooms.get(currentRoomId);
      if (!room) return;

      const { mediaUrl, time = 0, title = 'Video Stream' } = data || {};
      if (!mediaUrl) return;

      room.media = {
        mediaUrl,
        title,
        isPlaying: true,
        currentTime: Number(time) || 0,
        updatedAt: Date.now()
      };

      console.log(`[Room ${currentRoomId}] Media Loaded: ${mediaUrl} at ${time}s`);

      // Relay media:load to all clients in the room (including sender confirmation)
      socket.to(currentRoomId).emit('media:load', {
        mediaUrl: room.media.mediaUrl,
        time: room.media.currentTime,
        title: room.media.title,
        senderId: socket.id
      });
    } catch (err) {
      console.error('Error handling media:load:', err);
    }
  });

  // 3. Symmetric Media Controls (Play / Pause / Seek ±15s)
  socket.on('media:control', (data) => {
    try {
      if (!currentRoomId) return;
      const room = rooms.get(currentRoomId);
      if (!room) return;

      const { action, time = 0 } = data || {};
      const targetTime = Number(time) || 0;

      if (action === 'play') {
        room.media.isPlaying = true;
        room.media.currentTime = targetTime;
        room.media.updatedAt = Date.now();
      } else if (action === 'pause') {
        room.media.isPlaying = false;
        room.media.currentTime = targetTime;
        room.media.updatedAt = Date.now();
      } else if (action === 'seek') {
        room.media.currentTime = targetTime;
        room.media.updatedAt = Date.now();
      }

      console.log(`[Room ${currentRoomId}] Media Control: ${action} at ${targetTime}s from ${currentUser ? currentUser.userName : socket.id}`);

      // Relay to all OTHER clients in the room (socket.to suppresses echo back to sender)
      socket.to(currentRoomId).emit('media:control', {
        action,
        time: targetTime,
        senderId: socket.id,
        timestamp: Date.now()
      });
    } catch (err) {
      console.error('Error handling media:control:', err);
    }
  });

  // 4. Chat Messages & In-Memory Room History
  socket.on('chat:message', (data) => {
    try {
      if (!currentRoomId) return;
      const room = rooms.get(currentRoomId);
      if (!room) return;

      const { text } = data || {};
      if (!text || !text.trim()) return;

      const messageObj = {
        id: `msg-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`,
        senderId: socket.id,
        senderName: currentUser ? currentUser.userName : 'Anonymous',
        avatar: currentUser ? currentUser.avatar : '',
        text: text.trim(),
        timestamp: Date.now(),
        isHost: currentUser ? currentUser.isHost : false
      };

      // Store in memory per room
      room.messages.push(messageObj);
      if (room.messages.length > 300) {
        room.messages.shift();
      }

      // Broadcast to all participants in room
      io.to(currentRoomId).emit('chat:message', messageObj);
    } catch (err) {
      console.error('Error handling chat:message:', err);
    }
  });

  // 5. Chat Clear (Host-Only Enforcement)
  socket.on('chat:clear', () => {
    try {
      if (!currentRoomId) return;
      const room = rooms.get(currentRoomId);
      if (!room) return;

      if (!currentUser || !currentUser.isHost) {
        socket.emit('error', { message: 'Only the room host can clear chat history.' });
        return;
      }

      room.messages = [];
      console.log(`[Room ${currentRoomId}] Chat history cleared by Host ${currentUser.userName}`);

      // Broadcast blanking command to all clients in the room
      io.to(currentRoomId).emit('chat:cleared', {
        clearedBy: currentUser.userName,
        timestamp: Date.now()
      });
    } catch (err) {
      console.error('Error handling chat:clear:', err);
    }
  });

  // 6. WebRTC Audio Call Mesh Signaling
  socket.on('signal:offer', (data) => {
    try {
      const { targetId, offer } = data || {};
      if (targetId) {
        io.to(targetId).emit('signal:offer', {
          senderId: socket.id,
          senderName: currentUser ? currentUser.userName : '',
          offer
        });
      }
    } catch (err) {
      console.error('Error in signal:offer:', err);
    }
  });

  socket.on('signal:answer', (data) => {
    try {
      const { targetId, answer } = data || {};
      if (targetId) {
        io.to(targetId).emit('signal:answer', {
          senderId: socket.id,
          answer
        });
      }
    } catch (err) {
      console.error('Error in signal:answer:', err);
    }
  });

  socket.on('signal:candidate', (data) => {
    try {
      const { targetId, candidate } = data || {};
      if (targetId) {
        io.to(targetId).emit('signal:candidate', {
          senderId: socket.id,
          candidate
        });
      }
    } catch (err) {
      console.error('Error in signal:candidate:', err);
    }
  });

  // Voice Mute / Unmute State Broadcast
  socket.on('voice:toggle', (data) => {
    try {
      if (!currentRoomId || !currentUser) return;
      const room = rooms.get(currentRoomId);
      if (!room) return;

      currentUser.isMuted = !!data?.isMuted;
      io.to(currentRoomId).emit('voice:status_changed', {
        socketId: socket.id,
        isMuted: currentUser.isMuted
      });
    } catch (err) {
      console.error('Error in voice:toggle:', err);
    }
  });

  // 7. Disconnect / Leave
  const handleLeave = () => {
    if (!currentRoomId) return;
    const room = rooms.get(currentRoomId);
    if (!room) return;

    room.users.delete(socket.id);

    // If host left, elect new host if users remain
    if (room.hostSocketId === socket.id) {
      if (room.users.size > 0) {
        const nextUser = room.users.values().next().value;
        room.hostSocketId = nextUser.socketId;
        nextUser.isHost = true;
        io.to(nextUser.socketId).emit('room:host_assigned', { isHost: true });
        console.log(`[Room ${currentRoomId}] Transferred host to ${nextUser.userName}`);
      } else {
        room.hostSocketId = null;
      }
    }

    if (room.users.size === 0) {
      // Keep empty rooms briefly or clean up
      rooms.delete(currentRoomId);
      console.log(`[Room ${currentRoomId}] Cleaned up empty room`);
    } else {
      io.to(currentRoomId).emit('room:users_updated', {
        count: room.users.size,
        users: getSanitizedUsers(room)
      });
    }

    socket.leave(currentRoomId);
    currentRoomId = null;
  };

  socket.on('leave_room', handleLeave);
  socket.on('disconnect', handleLeave);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`=========================================`);
  console.log(` WatchRoom Server running on port ${PORT} `);
  console.log(` Endpoint: http://localhost:${PORT}      `);
  console.log(`=========================================`);
});
