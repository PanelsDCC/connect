# Installation Guide for Raspberry Pi

This guide covers installing panelsDCC-connect on a Raspberry Pi for headless operation.

## Hardware Requirements

- **Raspberry Pi 2 Model B or greater** (greater is recommended for better performance)
- MicroSD card (8GB minimum, 16GB or larger recommended)
- USB or serial connection to your DCC command station
- Network connection (Ethernet or Wi-Fi)

## Setting Up Raspberry Pi OS

### Using Raspberry Pi Imager

1. **Download Raspberry Pi Imager** from [raspberrypi.com/software](https://www.raspberrypi.com/software/)

2. **Install and launch Raspberry Pi Imager**

3. **Select Operating System**:
   - Click "Choose OS"
   - Select "Raspberry Pi OS (other)"
   - Choose "Raspberry Pi OS Lite (XX-bit)" where XX matches your chosen raspberry pi.
   - The Lite version is recommended as it doesn't include a desktop environment, saving resources

4. **Configure the Image**
   - **Enable SSH**: Check "Enable SSH" and set a password or add your SSH public key
   - **Set Username and Password**: Configure your user account
   - **Configure Wi-Fi** (if using Wi-Fi): Enter your network SSID and password
   - **Set Locale Settings**: Configure timezone and keyboard layout
   - **Enable Services**: Enable any additional services you need

5. **Write to SD Card**:
   - Insert your microSD card
   - Click "Choose Storage" and select your SD card
   - Click "Write" and wait for the process to complete

6. **Boot Your Raspberry Pi**:
   - Insert the SD card into your Raspberry Pi
   - Connect power, network (Ethernet or Wi-Fi), and boot
   - Wait a minute or two for the first boot to complete (it might even reboot once so be patient)

7. **Connect to Your Pi**:
   - If using Ethernet, find the Pi's IP address from your router
   - If using Wi-Fi, the Pi should connect automatically
   - SSH into your Pi:
     ```bash
     ssh pi@<pi-ip-address>
     ```
     (Replace `pi` with your configured username if different)

### Initial Setup

Once connected via SSH, update the system:

```bash
sudo apt update
sudo apt upgrade -y
sudo reboot
```

After reboot, reconnect via SSH.

## Installing panelsDCC-connect

### Download the Package

1. **Download the latest `.deb` package** from the [GitHub Releases](https://github.com/PanelsDCC/connect/releases) page

2. **Transfer to your Raspberry Pi**:
   - Option A: Download directly on the Pi:
     ```bash
    wget https://github.com/PanelsDCC/connect/releases/download/vX.X.X/panelsdcc-connect_X.X.X-X_all.deb
     ```
   - Option B: Download on your computer and transfer via SCP:
     ```bash
    scp panelsdcc-connect_X.X.X-X_all.deb pi@<pi-ip-address>:~/
     ```

### Install the Package

```bash
sudo apt install ./panelsdcc-connect_X.X.X-X_all.deb
```

Replace `X.X.X-X` with the actual version number of the downloaded package.

If you encounter dependency issues, run:

```bash
sudo apt install -f
```

This will install any missing dependencies and complete the installation.

### Automatic JMRI Installation

During package installation, the post-installation script will automatically:

1. **Check if JMRI is installed**
2. **If JMRI is not found**, automatically install the latest production release using `jmri-install --latest`
3. **Enable and start the panelsdcc-connect service**

The automatic JMRI installation will:
- Download the latest production release from GitHub
- Extract it to `/opt/jmri`
- Create a symlink at `/opt/jmri/current`
- Set appropriate permissions
- Restart the panelsdcc-connect service if it's running

**Note**: The automatic installation may take a few minutes depending on your internet connection speed, as it needs to download the JMRI release (typically 200-300MB).

### Manual JMRI Installation/Update

If you want to manually install or update JMRI, you can use the `jmri-install` script:

```bash
sudo jmri-install
```

This will:
- Show you all available production and test releases
- Allow you to select which version to install
- Optionally clean up old JMRI installations
- Optionally restart the service

To automatically install the latest production release without prompts:

```bash
sudo jmri-install --latest
```

## Verifying Installation

### Check Service Status

```bash
sudo systemctl status panelsdcc-connect
```

The service should be running and enabled (will start on boot).

### Check JMRI Installation

```bash
ls -la /opt/jmri/current
```

You should see the JMRI installation directory.

### Access the Web Interface

Open a web browser and navigate to:

```
http://<pi-ip-address>:9000
```

You should see the panelsdcc-connect web interface where you can:
- Auto-discover connected controllers
- Configure controller connections
- Test throttles and accessories
- Monitor real-time messages

### WiFi Mode Management (Bookworm+)

On Raspberry Pi OS Bookworm and newer, panelsdcc-connect integrates with NetworkManager to manage WiFi mode:

- **Client mode**: join your normal home/network WiFi.
- **Hotspot mode**: bring up a managed fallback AP named `panels-hotspot-XXXX` (`XXXX` from the WiFi MAC).

How it behaves:

- The hotspot password is generated automatically at package install and is shown in the web UI.
- The web UI includes a QR code so phones can join the hotspot quickly.
- Existing WiFi configured during Raspberry Pi imaging/first boot remains valid as a normal NetworkManager connection.
- If mode is client and no saved network can be connected after boot, fallback can activate the hotspot for recovery.

Security note:

- Treat this as a trusted local network management surface. Anyone with web access can view hotspot credentials and change WiFi mode/network settings.

## Service Management

### Start/Stop the Service

```bash
# Start the service
sudo systemctl start panelsdcc-connect

# Stop the service
sudo systemctl stop panelsdcc-connect

# Restart the service
sudo systemctl restart panelsdcc-connect
```

### Enable/Disable Auto-Start

```bash
# Enable service to start on boot
sudo systemctl enable panelsdcc-connect

# Disable auto-start
sudo systemctl disable panelsdcc-connect
```

### View Logs

```bash
# View recent logs
sudo journalctl -u panelsdcc-connect -n 50

# Follow logs in real-time
sudo journalctl -u panelsdcc-connect -f

# View logs since boot
sudo journalctl -u panelsdcc-connect -b
```

## Updating JMRI

To check for and install JMRI updates:

```bash
sudo jmri-install
```

This will show you all available releases. Select a newer version if available, or use:

```bash
sudo jmri-install --latest
```

to automatically install the latest production release.

After updating JMRI, restart the service:

```bash
sudo systemctl restart panelsdcc-connect
```

## Troubleshooting

### Service Won't Start

1. Check the service status:
   ```bash
   sudo systemctl status panelsdcc-connect
   ```

2. View error logs:
   ```bash
   sudo journalctl -u panelsdcc-connect -n 100
   ```

3. Verify JMRI is installed:
   ```bash
   ls -la /opt/jmri/current
   ```

4. If JMRI is missing, install it:
   ```bash
   sudo jmri-install --latest
   ```

### Controller Not Detected

1. Check USB/serial permissions:
   ```bash
   groups
   ```
   The `dcc-io` user should be in the `dialout` group (this is set up automatically).

2. Verify the device is connected:
   ```bash
   ls -la /dev/ttyUSB* /dev/ttyACM*
   ```

3. Check device permissions:
   ```bash
   ls -la /dev/ttyUSB0  # or your device
   ```

### Network Access Issues

If you can't access the web interface:

1. Check the service is running:
   ```bash
   sudo systemctl status panelsdcc-connect
   ```

2. Verify the port is listening:
   ```bash
   sudo netstat -tlnp | grep 9000
   ```

3. Check firewall settings (if enabled):
   ```bash
   sudo ufw status
   ```
   If UFW is active, allow port 9000:
   ```bash
   sudo ufw allow 9000/tcp
   ```

### JMRI Installation Fails

If automatic JMRI installation fails:

1. Check internet connectivity:
   ```bash
   ping -c 3 github.com
   ```

2. Verify required tools are installed:
   ```bash
   which curl jq tar
   ```

3. Try manual installation:
   ```bash
   sudo jmri-install
   ```

## Next Steps

After installation:

1. **Connect your DCC command station** via USB or serial
2. **Access the web interface** at `http://<pi-ip-address>:9000`
3. **Auto-discover controllers** using the web interface
4. **Configure connections** and assign controller roles
5. **Test your setup** using the throttle and accessory controls

For more information, see the [README.md](README.md) for API documentation and usage examples.
