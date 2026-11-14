# JS8 Firefly privacy policy (draft, adds Android)

Draft text for https://nr5p.com/js8-firefly-privacy.html. It is the policy already
published there for iPhone, iPad and Mac, with the Android parts added, so one page
and one URL can serve both stores. Set the effective date when you publish it.

---

## JS8 Firefly privacy policy

Effective: [date published]

JS8 Firefly is made by Nathan Rigg (NR5P). This policy covers JS8 Firefly for
iPhone, iPad, Mac and Android. The app collects no personal data. There are no
accounts, ads, analytics or tracking, and the app never sends anything about you to
me or to anyone else.

### What stays on your device

Your callsign, grid locator, station info, saved frequencies, watch list, radio
settings, inbox messages, messages you have stored for other stations, and the
stations and conversations you hear are stored only on your device, in the app's own
storage. Deleting the app deletes them.

On Android, if you have turned on device backup in your phone's settings, Android
may include the app's settings and inbox in the backup it keeps in your own Google
account, and restore them when you set up a new phone. That backup is run by Android
and controlled by you; I have no access to it.

### Permissions the app asks for

- **Microphone:** used to receive audio from your radio interface (such as a Digirig
  or a QMX) so JS8 signals can be decoded. Audio is processed live on the device. It
  is not recorded, saved or sent anywhere. On Android the app keeps listening with
  the screen off, and shows a notification the whole time it does, so it can decode
  and answer stations in the background. It stops when you close the app.
- **USB devices (Android):** used to send and receive audio through a USB audio
  interface and to control your radio through its USB serial port. Android asks you
  to allow each device.
- **Bluetooth / Nearby devices:** used only to find and connect to a Bluetooth CAT
  adapter on your radio. It is not used to work out your location.
- **Local network / Internet:** used only to control your radio through a server you
  set up, such as Hamlib rigctld or flrig. The app connects to the address you enter
  and to nothing else.
- **Notifications:** local alerts on your device when a message is addressed to you
  or something on your watch list is heard. They don't go through any server.
- **Location (optional):** used only to work out your Maidenhead grid locator, when
  you tap *Set grid from current location* or turn on *Update grid from GPS*. Your
  location is converted to a grid on the device and is never stored or sent anywhere
  by the app. Only the grid itself is kept, like one you type in. On Android the
  position comes from your phone's location service, which on most phones is Google
  Play services.

### What you transmit is public

JS8 is an amateur radio mode. Anything you send, including your callsign, grid
locator, station info and messages, goes out over the air and can be received by
anyone listening. Other stations, and services they choose to use, may log what they
hear. Amateur radio rules require you to identify with your callsign, and in most
countries they don't allow encrypted messages. Don't send anything over the air that
you want to keep private.

### Children

The app collects no information from anyone, including children.

### Changes

If this policy changes, the new version will be posted on this page with a new
effective date.

### Contact

Questions? Email nathan@nr5p.com.

JS8 Firefly is open source, so you can also check exactly what it does in the source
code on GitHub: github.com/NR5P/js8-firefly (iPhone, iPad and Mac) and
github.com/NR5P/js8-firefly-android (Android).
