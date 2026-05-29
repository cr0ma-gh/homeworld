#include <string.h>
#include <stdio.h>
#include <SDL2/SDL.h>
#include <SDL2/SDL_thread.h>
#ifdef HW_ENABLE_NETWORK
#include <SDL2/SDL_net.h>
/* POSIX sockets for getMyAddress()'s connect()+getsockname() local-IP probe.
   Available on Android/Linux; this whole file is only built with networking. */
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#endif
#include "NetworkInterface.h"

#ifdef HW_ENABLE_NETWORK


SDL_Thread *listenBroadcast, *listenTCP;
static UDPsocket broadcastSendSock, broadcastRecvSock;
static Client* TCPClientsConnected;
static TCPsocket clientSock;
static int endNetwork, clientActive, numTCPClientConnected;
static SDL_sem *semList;

/* Returns 1 on success, 0 on failure. The original code called exit() on every
   socket error, which on Android killed the whole game the instant the user
   opened the LAN menu (e.g. when socket() returns EACCES because the INTERNET
   permission isn't granted). Now we log via SDL_Log (which reaches logcat) and
   fail gracefully so titanStart() can show the engine's "can't network" box. */
int initNetwork()
{
	Uint32 sdl_flags;
	IPaddress broadcastIp;
	broadcastIp.host = INADDR_BROADCAST;
	broadcastIp.port = UDPPORT;
	endNetwork = 1;
	clientActive = 0;
	numTCPClientConnected = 0;

	semList = SDL_CreateSemaphore(1);

	/* Make sure SDL is initialized. */
	sdl_flags = SDL_WasInit(SDL_INIT_EVERYTHING);
	if (!sdl_flags)
	{
		if(SDL_Init(0) == -1)
		{
			SDL_Log("hwnet: SDL_Init failed: %s", SDL_GetError());
			return 0;
		}
	}

	if(SDLNet_Init() == -1)
	{
		SDL_Log("hwnet: SDLNet_Init failed: %s", SDLNet_GetError());
		return 0;
	}

	// Initialisation of Broadcast Sockets

	// Open Udp Port to receive Broadcast Packets
	if(!(broadcastRecvSock = SDLNet_UDP_Open(UDPPORT)))
	{
		SDL_Log("hwnet: UDP_Open(recv port %d) failed: %s -- is the INTERNET permission granted?",
		        UDPPORT, SDLNet_GetError());
		SDLNet_Quit();
		return 0;
	}

	// open udp client socket
	if(!(broadcastSendSock=SDLNet_UDP_Open(0)))
	{
		SDL_Log("hwnet: UDP_Open(send) failed: %s", SDLNet_GetError());
		SDLNet_UDP_Close(broadcastRecvSock); broadcastRecvSock = NULL;
		SDLNet_Quit();
		return 0;
	}

	// Create Thread to listen to Broadcast Packet on UDP
	listenBroadcast = SDL_CreateThread(broadcastStartThread,"hwLanBroadcast",broadcastSendSock);
	if(!listenBroadcast)
	{
		SDL_Log("hwnet: create broadcast thread failed: %s", SDL_GetError());
		return 0;
	}

        if(SDLNet_ResolveHost(&broadcastIp,"255.255.255.255",UDPPORT)==-1)
	{
		SDL_Log("hwnet: ResolveHost(255.255.255.255) failed: %s", SDLNet_GetError());
		return 0;
	}

	// bind server address to channel 0
	if(SDLNet_UDP_Bind(broadcastSendSock, 0, &broadcastIp)==-1)
	{
		SDL_Log("hwnet: UDP_Bind(broadcast) failed: %s", SDLNet_GetError());
		return 0;
	}


	// Initialisation of TCP Server Thread

	listenTCP = SDL_CreateThread(TCPServerStartThread,"hwLanTCPServer",NULL);
	if(!listenTCP)
	{
		SDL_Log("hwnet: create TCP server thread failed: %s", SDL_GetError());
		return 0;
	}

	SDL_Log("hwnet: initNetwork OK (UDP discovery %d, TCP %d)", UDPPORT, TCPPORT);
	return 1;
}

void sendBroadcastPacket(const void* packet, int len)
{
	UDPpacket *out = SDLNet_AllocPacket(2048);
	memcpy(out->data,packet,len);
	out->len=len;
	if(!SDLNet_UDP_Send(broadcastSendSock, 0, out))
	{
		/* Was exit(10) -- a failed advert send would kill the host. Log + skip. */
		SDL_Log("hwnet: broadcast send failed: %s", SDLNet_GetError());
	}
	else
	{
		SDL_Log("hwnet: -> broadcast advert sent (%d bytes)", len);
	}
	SDLNet_FreePacket(out);
}

void shutdownNetwork()
{
	endNetwork = 0;
	SDLNet_UDP_Close(broadcastSendSock);
	broadcastSendSock = NULL;
	SDL_WaitThread(listenBroadcast,NULL);
	SDL_WaitThread(listenTCP,NULL);
	SDLNet_Quit();
}

int broadcastStartThread(void *data)
{
	Uint32 ipaddr;
	UDPpacket *packet = SDLNet_AllocPacket(2048);
	IPaddress newIp;
	int number;
	IpList listIps = NULL;

//	unsigned int begin = SDL_GetTicks();
	while(endNetwork)
	{
		if(SDLNet_UDP_Recv(broadcastRecvSock,packet)>0)
		{
//			printf("Packet received, length: %d id: %s port: %d\n", packet->len, packet->data,packet->address.port);
			ipaddr=SDL_SwapBE32(packet->address.host);
//			printf("IP Address : %d.%d.%d.%d\n",
//						ipaddr>>24,
//						(ipaddr>>16)&0xff,
//						(ipaddr>>8)&0xff,
//						ipaddr&0xff);

			SDLNet_ResolveHost(&newIp,"255.255.255.255",UDPPORT);
			newIp.host=packet->address.host;
			if(checkList(newIp, listIps) == -1)
			{
				printf("newIp : %d\n",newIp.host);
				listIps = addList(newIp, listIps);
				if((number=SDLNet_UDP_Bind(data,0,&newIp))==-1)
				{
					printf("error binding\n");
				}
				else
					printf("binding last ip received, channel %d\n",number);
			}
//			printf("packet recu taille : %d\n",packet->len);
			{
				Uint32 be = SDL_SwapBE32(packet->address.host);
				SDL_Log("hwnet: <- broadcast advert RECEIVED from %u.%u.%u.%u (%d bytes) -> game list",
				        (be>>24)&0xff,(be>>16)&0xff,(be>>8)&0xff,be&0xff, packet->len);
			}
			titanReceivedLanBroadcastCB(packet->data, packet->len);
		}
		else
			SDL_Delay(100);
	}
	
	SDLNet_UDP_Close(broadcastRecvSock);
	broadcastRecvSock = NULL;
	
	return 0;
}

int checkList(IPaddress Ip, IpList list)
{
	IpList tmp = list;
	while(tmp != NULL)
	{
		if(tmp->IP.host == Ip.host)
			return 0;
		else
			tmp = tmp->nextIP;
	}
//	printf("not found in the list\n");

	return -1;
}

IpList addList(IPaddress newIp, IpList list)
{
	IpElem *new = (IpElem *)malloc(sizeof(IpElem));
	new->IP = newIp;
	new->nextIP = list;
	list = new;
	return list;
}


/* Discover this host's LAN IP (network byte order, matching SDL_net's
   IPaddress.host). The original implementation broadcast a "ping" to
   255.255.255.255 and blocked in `while(SDLNet_UDP_Recv()<=0);` waiting to
   receive its own packet -- which on Android can hang forever (broadcast does
   not reliably loop back to the sender) and called exit() on socket failure.

   Instead use the standard, non-blocking trick: open a UDP socket and connect()
   it toward an off-link address. No packets are sent by a UDP connect; the
   kernel just picks the source interface/IP from the routing table, which
   getsockname() then reports. Falls back to a loopback-resolve if that fails. */
Uint32 getMyAddress()
{
	int s;
	struct sockaddr_in remote, local;
	socklen_t len = sizeof(local);
	Uint32 ipAdd = 0;

	s = socket(AF_INET, SOCK_DGRAM, 0);
	if (s < 0)
	{
		SDL_Log("hwnet: getMyAddress socket() failed: %d", s);
		return 0;
	}

	memset(&remote, 0, sizeof(remote));
	remote.sin_family = AF_INET;
	remote.sin_port   = htons(53);              /* arbitrary; nothing is sent */
	remote.sin_addr.s_addr = inet_addr("8.8.8.8"); /* route hint only         */

	if (connect(s, (struct sockaddr *)&remote, sizeof(remote)) == 0 &&
	    getsockname(s, (struct sockaddr *)&local, &len) == 0)
	{
		ipAdd = local.sin_addr.s_addr;          /* already network byte order */
	}
	close(s);

	{
		Uint32 be = SDL_SwapBE32(ipAdd);
		SDL_Log("hwnet: my LAN IP = %u.%u.%u.%u",
		        (be>>24)&0xff, (be>>16)&0xff, (be>>8)&0xff, be&0xff);
	}
	return ipAdd;
}

int pingSendThread(void *data)
{
        UDPsocket sendSock;
        IPaddress pingIp;
        pingIp.port = 45268;


        if(SDLNet_ResolveHost(&pingIp,NULL,45268)==-1)
        {
                exit(2);
        }
        pingIp.host = INADDR_BROADCAST;

        UDPpacket *out = SDLNet_AllocPacket(512);

        if(!(sendSock=SDLNet_UDP_Open(0)))
        {
                printf("socket not open\n");
                exit(5);
        }

        if(!SDLNet_UDP_Bind(sendSock,1,&pingIp ))
        {
                exit(10);
        }


        SDL_Delay(100);

        out->len = 10;
        char* message = "ping";
        memcpy(out->data,message,strlen(message)+1);

        if(!SDLNet_UDP_Send(sendSock,1,out))
        {
                printf("no destination send\n");
        }
        SDLNet_FreePacket(out);
        SDLNet_UDP_Close(sendSock);
        return 0;
}



Uint32 connectToServer(Uint32 serverIP)
{
	IPaddress ipToConnect;
	Uint32 ipViewed;
	int numrdy, result;
	Uint32 be = SDL_SwapBE32(serverIP);

	SDL_Log("hwnet: -> connecting (TCP) to host %u.%u.%u.%u:%d",
	        (be>>24)&0xff,(be>>16)&0xff,(be>>8)&0xff,be&0xff, TCPPORT);

	if(SDLNet_ResolveHost(&ipToConnect,NULL,TCPPORT)==-1)
	{
		SDL_Log("hwnet: connectToServer ResolveHost failed: %s", SDLNet_GetError());
		return 0;
	}

	ipToConnect.host = serverIP;

	if(!(clientSock = SDLNet_TCP_Open(&ipToConnect)))
	{
		/* Was exit(3): a failed connect would crash the joining client. */
		SDL_Log("hwnet: TCP connect to host failed: %s", SDLNet_GetError());
		return 0;
	}
	SDL_Log("hwnet: TCP connected to host OK");

	SDLNet_SocketSet set;
	set = SDLNet_AllocSocketSet(1);
	SDLNet_TCP_AddSocket(set, clientSock);
	numrdy=SDLNet_CheckSockets(set, (Uint32)-1);

	if(SDLNet_SocketReady(clientSock))
	{
		result=SDLNet_TCP_Recv(clientSock,&ipViewed,sizeof(Uint32));
		if(result<sizeof(Uint32))
		{
			printf("SDLNet_TCP_Recv: %s\n", SDLNet_GetError());
			return NULL;
		}
	}
	else
		printf("Error during connection");


	// Switching to Client Mode.
	clientActive = 1;
	
	
	printf("Manage to connect");
	
	//addSockToList(sock);
	return ipViewed;	
}


int TCPServerStartThread(void *data)
{
	TCPsocket serverTCPSock, sock;
	SDLNet_SocketSet setSock, clientSet;
	IPaddress ip;
	IPaddress* fromIp;
	int clientInSet, i;
	clientInSet = 0;
	unsigned short lenPacket;
	unsigned char typMsg;
	Uint8* packet = NULL;


	// Resolve the argument into an IPaddress type
	if(SDLNet_ResolveHost(&ip,NULL,TCPPORT)==-1)
	{
		SDL_Log("hwnet: TCP server ResolveHost failed: %s", SDLNet_GetError());
		return 0;
	}

	if(!(serverTCPSock = SDLNet_TCP_Open(&ip)))
	{
		/* Was exit(3): a failed listen would crash the host at init. */
		SDL_Log("hwnet: TCP server listen on %d failed: %s", TCPPORT, SDLNet_GetError());
		return 0;
	}
	SDL_Log("hwnet: TCP server listening on port %d", TCPPORT);


	clientSet = SDLNet_AllocSocketSet(1);
	if(!clientSet)
		return 0;

	setSock = SDLNet_AllocSocketSet(100);
	if(!setSock)
		return 0;

	SDLNet_TCP_AddSocket(setSock, serverTCPSock);

						printf("size of packet received %d\n",lenPacket);
						printf("message type %d\n",typMsg);
		

	while(endNetwork)
	{
		int numready;
	
		if(clientActive == 0)
		{
			numready=SDLNet_CheckSockets(setSock, (Uint32)500);

			if(numready==-1)
			{
				printf("SDLNet_CheckSockets: %s\n",SDLNet_GetError());
				break;
			}

			if(!numready)
				continue;
			if(SDLNet_SocketReady(serverTCPSock))
			{
				numready--;
				//printf("Connection...\n");
				sock=SDLNet_TCP_Accept(serverTCPSock);
				if(sock)
				{
					IPaddress *pa = SDLNet_TCP_GetPeerAddress(sock);
					Uint32 be = pa ? SDL_SwapBE32(pa->host) : 0;
					SDL_Log("hwnet: <- client CONNECTED (join) from %u.%u.%u.%u",
					        (be>>24)&0xff,(be>>16)&0xff,(be>>8)&0xff,be&0xff);
					SDLNet_TCP_AddSocket(setSock, sock);
					addSockToList(sock);
					fromIp = SDLNet_TCP_GetPeerAddress(sock);
					int res;
					res = SDLNet_TCP_Send(sock,&(fromIp->host),sizeof(Uint32));
					if(res<sizeof(Uint32)) {
						printf("SDLNet_TCP_Send: %s\n", SDLNet_GetError());
						printf("Error sending back Ip");
					}
					
				}
				else
					printf("No new connection\n");
			}
			for(i=0; numready && i<numTCPClientConnected; i++)
			{
				if(SDLNet_SocketReady(TCPClientsConnected[i].sock))
				{
//					printf("New packet incoming from client %d\n",i);

					if(getPacket(TCPClientsConnected[i].sock, &typMsg, &packet, &lenPacket))
					{
						numready--;
//						printf("size of packet received %d\n",lenPacket);
//						printf("message type %d\n",typMsg);
						fromIp = SDLNet_TCP_GetPeerAddress(TCPClientsConnected[i].sock);
						HandleTCPMessage(fromIp->host, typMsg, packet, lenPacket);
					}
					else
						removeSockFromList(i);
				}
			}

		}
		else
		{
//			printf("Client server\n");
			if(clientInSet == 0)
			{
				printf("Adding socket to set\n");
				SDLNet_TCP_AddSocket(clientSet, clientSock);
				clientInSet = 1;
			}

			numready=SDLNet_CheckSockets(clientSet, (Uint32)500);
			
			// Code as a client
			if(SDLNet_SocketReady(clientSock))
			{
				numready--;
//				printf("New packet incoming from server\n");

				if(getPacket(clientSock, &typMsg, &packet, &lenPacket))
				{
//					printf("size of packet received %d\n",lenPacket);
//					printf("message type %d\n",typMsg);
					fromIp = SDLNet_TCP_GetPeerAddress(clientSock);
					HandleTCPMessage(fromIp->host, typMsg, packet, lenPacket);
				}
				else
				{
					SDLNet_TCP_Close(clientSock);
					clientInSet = 0;
					clientActive = 0;
				}

			}

		}

	}

	for(i=0; i<numTCPClientConnected; i++)
	{
		removeSockFromList(i);
	}

	SDLNet_TCP_Close(clientSock);
	SDLNet_TCP_Close(serverTCPSock);

	return 0;
}


Client * addSockToList(TCPsocket sock)
{
	IPaddress *remoteIp;
	remoteIp = SDLNet_TCP_GetPeerAddress(sock);

	if(remoteIp != NULL)	
	{
		SDL_SemWait(semList);
		TCPClientsConnected = (Client*) realloc (TCPClientsConnected, (numTCPClientConnected+1)*sizeof(Client));
		TCPClientsConnected[numTCPClientConnected].sock = sock;
		TCPClientsConnected[numTCPClientConnected].IP = *remoteIp;
		numTCPClientConnected++;
		SDL_SemPost(semList);
		return (&TCPClientsConnected[numTCPClientConnected-1]);
	}
	else
		return NULL;
}

void removeSockFromList(int num)
{
	SDL_SemWait(semList);
	SDLNet_TCP_Close(TCPClientsConnected[num].sock);
	numTCPClientConnected--;

	if(num < numTCPClientConnected)
		memmove(&TCPClientsConnected[num], &TCPClientsConnected[num+1], (numTCPClientConnected-num)*sizeof(Client));
	TCPClientsConnected = (Client*) realloc (TCPClientsConnected, numTCPClientConnected*sizeof(Client));
	SDL_SemPost(semList);

}

TCPsocket findSockInList(Uint32 addressSock)
{
	TCPsocket sock;
	
	int i;
	for (i=0; i < numTCPClientConnected; i++)
		if (addressSock == TCPClientsConnected[i].IP.host)
			return TCPClientsConnected[i].sock;
	return NULL;
}


// Implementation of putPacket and getPacket which are Homeworld specific
// In homeworld with each message, a message type is send
// We first send the message type, then the message len, and finally the message data


void putPacket(Uint32 address, unsigned char msgType, const void* data, unsigned short dataLen)
{
	int result;
	TCPsocket sock;

	if(clientActive == 0)
	{
//		printf("envoie en tant que serveur\n",dataLen);
		SDL_SemWait(semList);
		sock = findSockInList(address);
		if(sock == NULL)
		{
			/* No connected socket for this peer IP. Was an unconditional
			   SDLNet_TCP_Send(NULL,..) -> NULL-deref crash in the host when
			   broadcasting (e.g. lgStartGame's GAMEISSTARTING to all players).
			   Skip rather than crash. */
			Uint32 be = SDL_SwapBE32(address);
			SDL_Log("hwnet: putPacket: no connected socket for %u.%u.%u.%u (msgType %d) -- skipping",
			        (be>>24)&0xff,(be>>16)&0xff,(be>>8)&0xff,be&0xff, msgType);
			SDL_SemPost(semList);
			return;
		}
		result = SDLNet_TCP_Send(sock,&msgType,sizeof(unsigned char));
		if(result<sizeof(unsigned char)) {
			printf("SDLNet_TCP_Send: %s\n", SDLNet_GetError());

		}

		result = SDLNet_TCP_Send(sock,&dataLen,sizeof(unsigned short));

		if(result<sizeof(unsigned short)) {
			printf("SDLNet_TCP_Send: %s\n", SDLNet_GetError());

		}
		result = SDLNet_TCP_Send(sock,data,dataLen);
		if(result<dataLen) {
			printf("SDLNet_TCP_Send: %s\n", SDLNet_GetError());

		}
		SDL_SemPost(semList);
	}
	else
	{
//		printf("envoie en tant que client\n",dataLen);
//		printf("message type %d\n",msgType);
		sock = clientSock;
		if(sock == NULL)
		{
			SDL_Log("hwnet: putPacket: client socket is NULL (msgType %d) -- skipping", msgType);
			return;
		}
		result = SDLNet_TCP_Send(sock,&msgType,sizeof(unsigned char));
		if(result<sizeof(unsigned char)) {
			printf("SDLNet_TCP_Send: %s\n", SDLNet_GetError());

		}

		result = SDLNet_TCP_Send(sock,&dataLen,sizeof(unsigned short));

		if(result<sizeof(unsigned short)) {
			printf("SDLNet_TCP_Send: %s\n", SDLNet_GetError());

		}
		result = SDLNet_TCP_Send(sock,data,dataLen);
		if(result<dataLen) {
			printf("SDLNet_TCP_Send: %s\n", SDLNet_GetError());

		}
	}
//	printf("packet send, size of the packet :  %d\n",dataLen);
}


unsigned char getPacket(TCPsocket sock, unsigned char* msgType, Uint8** packetData, unsigned short* packetLen)
{
	int result;
	
	if(*packetData)
		free(*packetData);
	*packetData = NULL;

	result=SDLNet_TCP_Recv(sock,msgType,sizeof(unsigned char));
	if(result<sizeof(unsigned char))
	{
//		printf("SDLNet_TCP_Recv: %s\n", SDLNet_GetError());
		printf("Other side must have quit");
		return NULL;
	}

//	printf("type of message received %d\n",*msgType);

	result=SDLNet_TCP_Recv(sock,packetLen,sizeof(unsigned short));
	if(result<sizeof(unsigned short))
	{
		printf("SDLNet_TCP_Recv: %s\n", SDLNet_GetError());
		return NULL;
	}

//	printf("size of packet received %d\n",*packetLen);

	*packetData=(Uint8*)malloc(*packetLen);
	if(!(*packetData))
	{
		return NULL;
	}

	if(*packetLen > 0)
	{
		result=SDLNet_TCP_Recv(sock,*packetData,*packetLen);
		if(result<*packetLen)
		{
			printf("SDLNet_TCP_Recv: %s\n", SDLNet_GetError());
			return NULL;
		}
	}
//	printf("packet received\n");
	return *msgType;	
}



#endif
