<p align="center">
  <span style="font-size:48px; color:#ff4f4f; font-weight:bold;">
    Development has moved to a Premium Model
  </span><br>
  <span style="font-size:18px;">
    To receive continued updates and support, please visit 
    <a href="http://iudexindustries.co.uk/" style="color:#00af5c;">our new home</a>.
  </span>
</p>
----------------------------------------------------------------------------------------------------------------------------

**DDoSDefender** delivers high-performance mitigation by injecting a **sophisticated network handler** directly into your server's Netty pipeline. By inspecting handshake packets and source IP addresses in real time, the system applies dynamic **rate limiting** and selective **packet cancellation** to neutralise malicious bursts instantly. Furthermore, **DDoSDefender** intercepts LoginStart packets using a **state-of-the-art detection suite** that executes 27 individual heuristic checks — from username entropy, keyboard-walk and edit-distance clustering to UUID fingerprinting and swarm-pattern detection — achieving an extremely high bot detection rate without sacrificing performance.

*This figure is based on controlled testing using bots with varied naming schemes.*

![Replace this with a description](https://cdn.modrinth.com/data/cached_images/2881511fc48443473ca01cd565eccaf3ae11b5a4.png)

![Replace this with a description](https://cdn.modrinth.com/data/cached_images/a421482686155a96c2d0a7db942e06e7e182bcb8.png)

![Replace this with a description](https://cdn.modrinth.com/data/cached_images/3e0198137ae63b5dbdaefdac211cd2c337eabc97.png)

**DDoSDefender** features our proprietary **"FastPass"** system, which allows previously verified players to bypass security checks on subsequent logins from the same network. This **rewards your community** with **seamless connections** while keeping your **server protected** from disruptions.

`/ii fastpass add <playername>` — (Run as an op if a player cannot join)

Administrators can also run `/ii test <playername>` to instantly see how any name scores against the full detection suite — useful for tuning thresholds or investigating a specific player before taking action.

*DDoSDefender focuses exclusively on managing and filtering application-level connection attempts. It is important to note that it does not—and cannot—protect your physical network infrastructure, as no software-level plugin can mitigate volumetric attacks at the ISP level.*

*For comprehensive protection, we strongly recommend a layered security strategy: pair DDoSDefender/DDoSDefenderLite with a network-level proxy like TCPShield. While TCPShield shields your IP and absorbs massive network-layer floods, DDoSDefender provides the "last mile" of defence by intelligently filtering the complex, Minecraft-specific handshake exploits that proxies might pass through.*
