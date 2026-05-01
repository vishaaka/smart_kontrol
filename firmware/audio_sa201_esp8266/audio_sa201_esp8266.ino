#include <EEPROM.h>
#include <ESP8266WebServer.h>
#include <ESP8266WiFi.h>
#include <user_interface.h>

namespace {
constexpr uint32_t kConfigMagic = 0x4B415032;
constexpr int kEepromSize = 1024;
constexpr unsigned long kConnectTimeoutMs = 15000;
constexpr unsigned long kReconnectIntervalMs = 30000;
constexpr uint16_t kHttpPort = 80;
constexpr uint16_t kDefaultPulseMs = 500;
constexpr char kDefaultApPassword[] = "KapiAc123";
const IPAddress kApIp(192, 168, 4, 1);
const IPAddress kApGateway(192, 168, 4, 1);
const IPAddress kApSubnet(255, 255, 255, 0);
const IPAddress kApLeaseStart(192, 168, 4, 10);
const IPAddress kApLeaseEnd(192, 168, 4, 19);

enum RelayChannel : uint8_t {
  RELAY_1 = 1,
  RELAY_2 = 2,
};

enum RelayMode : uint8_t {
  MODE_MOMENTARY = 0,
  MODE_LATCH = 1,
};

struct ChannelConfig {
  char label[24];
  uint8_t mode;
  uint16_t pulseMs;
  uint8_t reserved;
};

struct DeviceConfig {
  uint32_t magic;
  char wifiSsid[32];
  char wifiPassword[64];
  char apiToken[32];
  char deviceName[32];
  uint8_t primaryChannel;
  ChannelConfig channels[2];
};

DeviceConfig config{};
ESP8266WebServer server(kHttpPort);
bool apModeActive = false;
bool relayStates[2] = {false, false};
unsigned long lastReconnectAttemptMs = 0;
unsigned long lastConnectAttemptStartedMs = 0;
unsigned long lastConnectAttemptEndedMs = 0;
uint32_t connectAttemptCount = 0;
char lastConnectMessage[48] = "boot";

String chipIdHex() {
  char buffer[7];
  snprintf(buffer, sizeof(buffer), "%06X", ESP.getChipId());
  return String(buffer);
}

void writeString(char* target, size_t size, const String& value) {
  if (size == 0) {
    return;
  }

  strncpy(target, value.c_str(), size - 1);
  target[size - 1] = '\0';
}

String htmlEscape(String input) {
  input.replace("&", "&amp;");
  input.replace("<", "&lt;");
  input.replace(">", "&gt;");
  input.replace("\"", "&quot;");
  return input;
}

String jsonEscape(String input) {
  input.replace("\\", "\\\\");
  input.replace("\"", "\\\"");
  input.replace("\n", "\\n");
  input.replace("\r", "\\r");
  return input;
}

void writeMessage(char* target, size_t size, const String& value) {
  writeString(target, size, value);
}

bool isValidMode(uint8_t mode) {
  return mode == MODE_MOMENTARY || mode == MODE_LATCH;
}

int channelIndexFromNumber(int channelNumber) {
  if (channelNumber == 1) {
    return 0;
  }
  if (channelNumber == 2) {
    return 1;
  }
  return -1;
}

uint16_t clampPulse(int value) {
  if (value < 200) {
    return 200;
  }
  if (value > 3000) {
    return 3000;
  }
  return static_cast<uint16_t>(value);
}

RelayMode modeFromString(String value) {
  value.toLowerCase();
  if (value == "latch") {
    return MODE_LATCH;
  }
  return MODE_MOMENTARY;
}

String modeLabel(uint8_t mode) {
  return mode == MODE_LATCH ? "latch" : "momentary";
}

String wifiStatusLabel(wl_status_t status) {
  switch (status) {
    case WL_CONNECTED:
      return "connected";
    case WL_NO_SSID_AVAIL:
      return "ssid_not_found";
    case WL_CONNECT_FAILED:
      return "connect_failed";
    case WL_CONNECTION_LOST:
      return "connection_lost";
    case WL_WRONG_PASSWORD:
      return "wrong_password";
    case WL_DISCONNECTED:
      return "disconnected";
    case WL_IDLE_STATUS:
    default:
      return "idle";
  }
}

void applyDefaults() {
  memset(&config, 0, sizeof(config));
  config.magic = kConfigMagic;
  config.primaryChannel = 1;
  writeString(config.apiToken, sizeof(config.apiToken), "door-" + chipIdHex());
  writeString(config.deviceName, sizeof(config.deviceName), "KapiAc-" + chipIdHex());

  writeString(config.channels[0].label, sizeof(config.channels[0].label), "Kapi Ac");
  config.channels[0].mode = MODE_MOMENTARY;
  config.channels[0].pulseMs = kDefaultPulseMs;

  writeString(config.channels[1].label, sizeof(config.channels[1].label), "Yedek Role");
  config.channels[1].mode = MODE_MOMENTARY;
  config.channels[1].pulseMs = kDefaultPulseMs;
}

void normalizeConfig() {
  if (config.magic != kConfigMagic) {
    applyDefaults();
    return;
  }

  if (config.primaryChannel != 1 && config.primaryChannel != 2) {
    config.primaryChannel = 1;
  }

  for (int i = 0; i < 2; ++i) {
    if (!isValidMode(config.channels[i].mode)) {
      config.channels[i].mode = MODE_MOMENTARY;
    }
    config.channels[i].pulseMs = clampPulse(config.channels[i].pulseMs == 0 ? kDefaultPulseMs : config.channels[i].pulseMs);
    if (strlen(config.channels[i].label) == 0) {
      writeString(
          config.channels[i].label,
          sizeof(config.channels[i].label),
          i == 0 ? "Kapi Ac" : "Yedek Role");
    }
  }

  if (strlen(config.deviceName) == 0) {
    writeString(config.deviceName, sizeof(config.deviceName), "KapiAc-" + chipIdHex());
  }
}

void saveConfig() {
  EEPROM.put(0, config);
  EEPROM.commit();
}

void loadConfig() {
  EEPROM.begin(kEepromSize);
  EEPROM.get(0, config);
  normalizeConfig();
  saveConfig();
}

void sendRelayCommand(RelayChannel channel, bool state) {
  uint8_t command[4] = {
      0xA0,
      static_cast<uint8_t>(channel),
      static_cast<uint8_t>(state ? 0x01 : 0x00),
      0x00,
  };
  command[3] = command[0] + command[1] + command[2];
  Serial.write(command, sizeof(command));
  Serial.flush();
}

void setRelayState(int channelNumber, bool state) {
  int channelIndex = channelIndexFromNumber(channelNumber);
  if (channelIndex < 0) {
    return;
  }

  sendRelayCommand(static_cast<RelayChannel>(channelNumber), state);
  relayStates[channelIndex] = state;
}

void pulseRelay(int channelNumber) {
  int channelIndex = channelIndexFromNumber(channelNumber);
  if (channelIndex < 0) {
    return;
  }

  setRelayState(channelNumber, true);
  delay(config.channels[channelIndex].pulseMs);
  setRelayState(channelNumber, false);
}

String recommendedActionForChannel(int channelNumber) {
  int channelIndex = channelIndexFromNumber(channelNumber);
  if (channelIndex < 0) {
    return "trigger";
  }

  return config.channels[channelIndex].mode == MODE_LATCH ? "toggle" : "trigger";
}

bool executeRelayAction(int channelNumber, String action, String& message) {
  int channelIndex = channelIndexFromNumber(channelNumber);
  if (channelIndex < 0) {
    message = "invalid_channel";
    return false;
  }

  action.toLowerCase();
  if (action.length() == 0) {
    action = recommendedActionForChannel(channelNumber);
  }

  if (action == "trigger") {
    pulseRelay(channelNumber);
    message = "pulse_sent";
    return true;
  }

  if (action == "toggle") {
    bool nextState = !relayStates[channelIndex];
    setRelayState(channelNumber, nextState);
    message = nextState ? "latched_on" : "latched_off";
    return true;
  }

  if (action == "on") {
    setRelayState(channelNumber, true);
    message = "latched_on";
    return true;
  }

  if (action == "off") {
    setRelayState(channelNumber, false);
    message = "latched_off";
    return true;
  }

  message = "invalid_action";
  return false;
}

String wifiModeLabel() {
  if (WiFi.status() == WL_CONNECTED) {
    return "station";
  }
  if (apModeActive) {
    return "access-point";
  }
  return "offline";
}

bool hasToken() {
  return strlen(config.apiToken) > 0;
}

bool isAuthorized() {
  if (!hasToken()) {
    return true;
  }

  String token;
  if (server.hasArg("authToken")) {
    token = server.arg("authToken");
  } else if (server.hasHeader("X-Api-Token")) {
    token = server.header("X-Api-Token");
  } else if (server.hasHeader("Authorization")) {
    String auth = server.header("Authorization");
    if (auth.startsWith("Bearer ")) {
      token = auth.substring(7);
    }
  } else if (server.hasArg("token")) {
    token = server.arg("token");
  }

  return token.equals(String(config.apiToken));
}

bool canBootstrapProvision() {
  if (isAuthorized()) {
    return true;
  }

  if (!apModeActive) {
    return false;
  }

  return server.arg("bootstrapPassword") == String(kDefaultApPassword);
}

bool canReadBootstrapStatus() {
  return apModeActive;
}

void sendJson(int statusCode, const String& body) {
  server.send(statusCode, "application/json; charset=utf-8", body);
}

String channelsJson() {
  String json = "[";
  for (int i = 0; i < 2; ++i) {
    if (i > 0) {
      json += ",";
    }
    json += "{";
    json += "\"id\":" + String(i + 1) + ",";
    json += "\"label\":\"" + jsonEscape(String(config.channels[i].label)) + "\",";
    json += "\"mode\":\"" + modeLabel(config.channels[i].mode) + "\",";
    json += "\"pulseMs\":" + String(config.channels[i].pulseMs) + ",";
    json += "\"state\":" + String(relayStates[i] ? "true" : "false") + ",";
    json += "\"recommendedAction\":\"" + recommendedActionForChannel(i + 1) + "\"";
    json += "}";
  }
  json += "]";
  return json;
}

String statusJson() {
  String json = "{";
  json += "\"deviceName\":\"" + jsonEscape(String(config.deviceName)) + "\",";
  json += "\"mode\":\"" + jsonEscape(wifiModeLabel()) + "\",";
  json += "\"stationConnected\":" + String(WiFi.status() == WL_CONNECTED ? "true" : "false") + ",";
  json += "\"ip\":\"" + jsonEscape(WiFi.localIP().toString()) + "\",";
  json += "\"apIp\":\"" + jsonEscape(WiFi.softAPIP().toString()) + "\",";
  json += "\"ssid\":\"" + jsonEscape(String(config.wifiSsid)) + "\",";
  json += "\"wifiStatus\":\"" + jsonEscape(wifiStatusLabel(WiFi.status())) + "\",";
  json += "\"lastConnectMessage\":\"" + jsonEscape(String(lastConnectMessage)) + "\",";
  json += "\"connectAttemptCount\":" + String(connectAttemptCount) + ",";
  json += "\"lastConnectAttemptStartedMs\":" + String(lastConnectAttemptStartedMs) + ",";
  json += "\"lastConnectAttemptEndedMs\":" + String(lastConnectAttemptEndedMs) + ",";
  json += "\"primaryChannel\":" + String(config.primaryChannel) + ",";
  json += "\"channels\":" + channelsJson();
  json += "}";
  return json;
}

String currentBaseUrl() {
  if (WiFi.status() == WL_CONNECTED) {
    return "http://" + WiFi.localIP().toString();
  }
  if (apModeActive) {
    return "http://" + WiFi.softAPIP().toString();
  }
  return "http://192.168.4.1";
}

String bigButtonHtml(int channelNumber) {
  int idx = channelIndexFromNumber(channelNumber);
  String html;
  const bool isLatch = config.channels[idx].mode == MODE_LATCH;
  html += "<form class='big-form' method='post' action='/api/channel'>";
  html += "<div class='big-card'>";
  html += "<div class='big-top'>Kanal " + String(channelNumber) + "</div>";
  html += "<div class='big-label'>" + htmlEscape(String(config.channels[idx].label)) + "</div>";
  html += "<div class='big-meta'>" + String(isLatch ? "Latch toggle" : "Anlik tetik") + " / " + String(config.channels[idx].pulseMs) + " ms</div>";
  html += "<div class='big-state'>" + String(relayStates[idx] ? "Durum: acik" : "Durum: kapali") + "</div>";
  html += "<input type='hidden' name='token' value='" + htmlEscape(String(config.apiToken)) + "'>";
  html += "<input type='hidden' name='channel' value='" + String(channelNumber) + "'>";
  html += "<input type='hidden' name='action' value='" + String(isLatch ? "toggle" : "trigger") + "'>";
  html += "<button class='big-button " + String(channelNumber == 1 ? "accent-1" : "accent-2") + "' type='submit'>" + htmlEscape(String(config.channels[idx].label)) + "</button>";
  html += "</div></form>";
  return html;
}

void handleStatus() {
  if (!isAuthorized() && !canReadBootstrapStatus()) {
    sendJson(401, "{\"ok\":false,\"message\":\"unauthorized\"}");
    return;
  }

  sendJson(200, statusJson());
}

void handleOpen() {
  if (!isAuthorized()) {
    sendJson(401, "{\"ok\":false,\"message\":\"unauthorized\"}");
    return;
  }

  String message;
  executeRelayAction(1, "trigger", message);
  sendJson(200, "{\"ok\":true,\"message\":\"" + message + "\"}");
}

void handleChannelAction() {
  if (!isAuthorized()) {
    sendJson(401, "{\"ok\":false,\"message\":\"unauthorized\"}");
    return;
  }

  int channel = server.arg("channel").toInt();
  String action = server.arg("action");
  String message;
  bool ok = executeRelayAction(channel, action, message);
  if (!ok) {
    sendJson(400, "{\"ok\":false,\"message\":\"" + message + "\"}");
    return;
  }

  int idx = channelIndexFromNumber(channel);
  String json = "{";
  json += "\"ok\":true,";
  json += "\"message\":\"" + message + "\",";
  json += "\"channel\":" + String(channel) + ",";
  json += "\"state\":" + String(relayStates[idx] ? "true" : "false");
  json += "}";
  sendJson(200, json);
}

void updateConfigFieldIfPresent() {
  if (server.hasArg("deviceName")) {
    String deviceName = server.arg("deviceName");
    writeString(config.deviceName, sizeof(config.deviceName), deviceName.length() ? deviceName : "KapiAc-" + chipIdHex());
  }

  if (server.hasArg("newToken")) {
    writeString(config.apiToken, sizeof(config.apiToken), server.arg("newToken"));
  } else if (server.hasArg("token")) {
    writeString(config.apiToken, sizeof(config.apiToken), server.arg("token"));
  }

  if (server.hasArg("ssid")) {
    writeString(config.wifiSsid, sizeof(config.wifiSsid), server.arg("ssid"));
  }

  if (server.hasArg("password")) {
    writeString(config.wifiPassword, sizeof(config.wifiPassword), server.arg("password"));
  }

  if (server.hasArg("primaryChannel")) {
    int primaryChannel = server.arg("primaryChannel").toInt();
    config.primaryChannel = primaryChannel == 2 ? 2 : 1;
  }

  for (int channel = 1; channel <= 2; ++channel) {
    int idx = channel - 1;
    String labelKey = "ch" + String(channel) + "Label";
    String modeKey = "ch" + String(channel) + "Mode";
    String pulseKey = "ch" + String(channel) + "PulseMs";

    if (server.hasArg(labelKey)) {
      writeString(config.channels[idx].label, sizeof(config.channels[idx].label), server.arg(labelKey));
    }

    if (server.hasArg(modeKey)) {
      config.channels[idx].mode = modeFromString(server.arg(modeKey));
    }

    if (server.hasArg(pulseKey)) {
      config.channels[idx].pulseMs = clampPulse(server.arg(pulseKey).toInt());
    }
  }

  normalizeConfig();
  saveConfig();
}

void handleConfigGet() {
  if (!isAuthorized() && !canReadBootstrapStatus()) {
    sendJson(401, "{\"ok\":false,\"message\":\"unauthorized\"}");
    return;
  }

  sendJson(200, statusJson());
}

void handleConfigPost() {
  if (!isAuthorized()) {
    sendJson(401, "{\"ok\":false,\"message\":\"unauthorized\"}");
    return;
  }

  updateConfigFieldIfPresent();

  String json = "{";
  json += "\"ok\":true,";
  json += "\"message\":\"config_saved\",";
  json += "\"status\":" + statusJson();
  json += "}";
  sendJson(200, json);
}

void handleProvision() {
  if (!canBootstrapProvision()) {
    sendJson(401, "{\"ok\":false,\"message\":\"unauthorized\"}");
    return;
  }

  if (!server.hasArg("ssid")) {
    sendJson(400, "{\"ok\":false,\"message\":\"missing_ssid\"}");
    return;
  }

  updateConfigFieldIfPresent();

  String json = "{";
  json += "\"ok\":true,";
  json += "\"message\":\"provision_saved_restarting\",";
  json += "\"status\":" + statusJson();
  json += "}";
  sendJson(200, json);
  delay(1200);
  ESP.restart();
}

void handleRoot() {
  String html;
  html.reserve(11000);
  html += "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>";
  html += "<title>Kapi Ac</title><style>";
  html += "body{font-family:Arial,sans-serif;background:#f3efe7;color:#18212f;max-width:760px;margin:0 auto;padding:16px;}";
  html += ".card{background:#fff;border-radius:18px;padding:18px;margin-bottom:16px;box-shadow:0 8px 28px rgba(0,0,0,.08);}";
  html += ".hero{background:linear-gradient(145deg,#0f766e,#0b4f49);color:#fff;}";
  html += ".button-grid{display:grid;grid-template-columns:1fr;gap:16px;margin:16px 0 18px;}";
  html += ".big-form{margin:0;}.big-card{background:#fff;border-radius:22px;padding:18px;box-shadow:0 10px 28px rgba(0,0,0,.08);}";
  html += ".big-top{font-size:13px;text-transform:uppercase;letter-spacing:.08em;color:#64748b;font-weight:700;}";
  html += ".big-label{font-size:28px;line-height:1.1;font-weight:800;margin:8px 0 10px;}";
  html += ".big-meta,.big-state{font-size:14px;color:#475569;margin:4px 0;}";
  html += ".big-button{margin-top:16px;width:100%;border:none;border-radius:18px;padding:22px 18px;font-size:22px;font-weight:800;color:#fff;cursor:pointer;}";
  html += ".accent-1{background:linear-gradient(135deg,#b45309,#ea580c);} .accent-2{background:linear-gradient(135deg,#1d4ed8,#0f766e);}";
  html += "input,select,button{font-size:16px;padding:12px;border-radius:12px;border:1px solid #d1d5db;width:100%;box-sizing:border-box;}";
  html += ".save-btn{background:#18212f;color:#fff;border:none;font-weight:700;cursor:pointer;}";
  html += "label{display:block;margin:12px 0 6px;font-weight:700;}small{color:#6b7280;}code{background:#e2e8f0;padding:2px 6px;border-radius:6px;}";
  html += ".mini{font-size:14px;color:#e2e8f0;}.section-title{font-size:20px;font-weight:800;margin:0 0 10px;}";
  html += "@media (min-width:700px){.button-grid{grid-template-columns:1fr 1fr;}}";
  html += "</style></head><body>";
  html += "<div class='card hero'><h1>" + htmlEscape(String(config.deviceName)) + "</h1>";
  html += "<p class='mini'>Mod: <strong>" + wifiModeLabel() + "</strong></p>";
  html += "<p class='mini'>Adres: <code>" + currentBaseUrl() + "</code></p>";
  html += "<p class='mini'>AP: <code>KapiAc-" + chipIdHex() + "</code> / Sifre: <code>" + String(kDefaultApPassword) + "</code></p>";
  html += "<p class='mini'>AP DHCP havuzu: <code>192.168.4.10 - 192.168.4.19</code></p></div>";

  html += "<div class='button-grid'>";
  html += bigButtonHtml(1);
  html += bigButtonHtml(2);
  html += "</div>";

  html += "<div class='card'><div class='section-title'>Wi-Fi ve Kanal Ayarlari</div>";
  html += "<form method='post' action='/save'>";
  html += "<label>Cihaz adi</label><input name='deviceName' value='" + htmlEscape(String(config.deviceName)) + "'>";
  html += "<input type='hidden' name='authToken' value='" + htmlEscape(String(config.apiToken)) + "'>";
  html += "<label>API token</label><input name='newToken' value='" + htmlEscape(String(config.apiToken)) + "'>";
  html += "<label>Wi-Fi adi (SSID)</label><input name='ssid' value='" + htmlEscape(String(config.wifiSsid)) + "'>";
  html += "<label>Wi-Fi sifresi</label><input name='password' type='password' value='" + htmlEscape(String(config.wifiPassword)) + "'>";
  html += "<label>Tek buton mantigi icin ana kanal</label><select name='primaryChannel'>";
  html += "<option value='1'" + String(config.primaryChannel == 1 ? " selected" : "") + ">Kanal 1</option>";
  html += "<option value='2'" + String(config.primaryChannel == 2 ? " selected" : "") + ">Kanal 2</option>";
  html += "</select>";
  for (int channel = 1; channel <= 2; ++channel) {
    int idx = channel - 1;
    html += "<hr><h3>Kanal " + String(channel) + "</h3>";
    html += "<label>Etiket</label><input name='ch" + String(channel) + "Label' value='" + htmlEscape(String(config.channels[idx].label)) + "'>";
    html += "<label>Mod</label><select name='ch" + String(channel) + "Mode'>";
    html += "<option value='momentary'" + String(config.channels[idx].mode == MODE_MOMENTARY ? " selected" : "") + ">Momentary (anlik)</option>";
    html += "<option value='latch'" + String(config.channels[idx].mode == MODE_LATCH ? " selected" : "") + ">Latch (toggle)</option>";
    html += "</select>";
    html += "<label>Darbe suresi (200-3000 ms)</label><input name='ch" + String(channel) + "PulseMs' type='number' min='200' max='3000' value='" + String(config.channels[idx].pulseMs) + "'>";
  }
  html += "<button class='save-btn' type='submit'>Kaydet ve Yeniden Baslat</button></form>";
  html += "<p><small>Momentary modda buyuk buton roleyi secilen sure kadar ceker. Latch modda buton her basista ac/kapa toggle yapar.</small></p></div>";

  html += "<div class='card'><div class='section-title'>Kullanim</div>";
  html += "<p>1. Ilk kurulumda telefondan bu AP'ye baglan.</p>";
  html += "<p>2. Wi-Fi adi ve sifresini yazip kaydet.</p>";
  html += "<p>3. Cihaz ev agina baglaninca AP kapanir.</p>";
  html += "<p>4. Sonra modemde verdigi IP ile ayni sayfayi ac.</p>";
  html += "<p>5. ESP8266 sadece 2.4 GHz aglara baglanir. WPA2 kullan; SSID ve sifre buyuk-kucuk harfe duyarlidir.</p>";
  html += "</div>";

  html += "<div class='card'><div class='section-title'>Wi-Fi Tani</div>";
  html += "<p>Kayitli SSID: <code>" + htmlEscape(String(config.wifiSsid)) + "</code></p>";
  html += "<p>Canli durum: <code>" + htmlEscape(wifiStatusLabel(WiFi.status())) + "</code></p>";
  html += "<p>Son sonuc: <code>" + htmlEscape(String(lastConnectMessage)) + "</code></p>";
  html += "<p>Deneme sayisi: <code>" + String(connectAttemptCount) + "</code></p>";
  html += "<p><small><code>wrong_password</code> sifre hatali, <code>ssid_not_found</code> ag gorunmuyor, <code>connect_failed</code> ise genelde guvenlik/uyumluluk sorunudur.</small></p>";
  html += "</div></body></html>";

  server.send(200, "text/html; charset=utf-8", html);
}

void handleSave() {
  if (!isAuthorized()) {
    server.send(401, "text/html; charset=utf-8", "<html><body><h1>401</h1><p>Yetkisiz istek.</p></body></html>");
    return;
  }
  updateConfigFieldIfPresent();
  server.send(200, "text/html; charset=utf-8", "<html><body><h1>Kaydedildi</h1><p>Cihaz yeniden baslatiliyor...</p></body></html>");
  delay(1000);
  ESP.restart();
}

void handleNotFound() {
  sendJson(404, "{\"ok\":false,\"message\":\"not_found\"}");
}

void configureApDhcpPool() {
  wifi_softap_dhcps_stop();

  struct dhcps_lease lease;
  memset(&lease, 0, sizeof(lease));
  lease.enable = true;
  lease.start_ip.addr = static_cast<uint32_t>(kApLeaseStart);
  lease.end_ip.addr = static_cast<uint32_t>(kApLeaseEnd);
  wifi_softap_set_dhcps_lease(&lease);
  wifi_softap_set_dhcps_lease_time(120);
  wifi_softap_dhcps_start();
}

void stopAccessPoint() {
  if (!apModeActive) {
    return;
  }

  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  apModeActive = false;
}

void startAccessPoint() {
  String apSsid = "KapiAc-" + chipIdHex();
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAPConfig(kApIp, kApGateway, kApSubnet);
  WiFi.softAP(apSsid.c_str(), kDefaultApPassword);
  configureApDhcpPool();
  apModeActive = true;
}

bool connectToSavedWifi() {
  if (strlen(config.wifiSsid) == 0) {
    writeMessage(lastConnectMessage, sizeof(lastConnectMessage), "ssid_missing");
    return false;
  }

  connectAttemptCount++;
  lastConnectAttemptStartedMs = millis();
  writeMessage(lastConnectMessage, sizeof(lastConnectMessage), "connecting");

  WiFi.persistent(false);
  WiFi.setAutoReconnect(true);
  WiFi.mode(apModeActive ? WIFI_AP_STA : WIFI_STA);
  WiFi.disconnect(false);
  delay(200);
  WiFi.hostname(config.deviceName);
  WiFi.begin(config.wifiSsid, config.wifiPassword);

  unsigned long startedAt = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startedAt < kConnectTimeoutMs) {
    delay(250);
  }

  lastConnectAttemptEndedMs = millis();

  if (WiFi.status() == WL_CONNECTED) {
    writeMessage(lastConnectMessage, sizeof(lastConnectMessage), "connected");
    stopAccessPoint();
  } else {
    writeMessage(lastConnectMessage, sizeof(lastConnectMessage), wifiStatusLabel(WiFi.status()));
  }

  return WiFi.status() == WL_CONNECTED;
}

void ensureConnectivity() {
  if (WiFi.status() == WL_CONNECTED) {
    stopAccessPoint();
    return;
  }

  if (!apModeActive) {
    startAccessPoint();
  }

  unsigned long now = millis();
  if (now - lastReconnectAttemptMs < kReconnectIntervalMs) {
    return;
  }

  lastReconnectAttemptMs = now;
  if (strlen(config.wifiSsid) > 0) {
    connectToSavedWifi();
  }
}

void setupWebServer() {
  server.collectHeaders("X-Api-Token", "Authorization");
  server.on("/", HTTP_GET, handleRoot);
  server.on("/save", HTTP_POST, handleSave);
  server.on("/api/status", HTTP_GET, handleStatus);
  server.on("/api/open", HTTP_POST, handleOpen);
  server.on("/api/open", HTTP_GET, handleOpen);
  server.on("/api/channel", HTTP_POST, handleChannelAction);
  server.on("/api/channel", HTTP_GET, handleChannelAction);
  server.on("/api/config", HTTP_GET, handleConfigGet);
  server.on("/api/config", HTTP_POST, handleConfigPost);
  server.on("/api/provision", HTTP_POST, handleProvision);
  server.onNotFound(handleNotFound);
  server.begin();
}
}  // namespace

void setup() {
  Serial.begin(115200);
  delay(100);
  loadConfig();
  setRelayState(RELAY_1, false);
  setRelayState(RELAY_2, false);

  bool connected = connectToSavedWifi();
  if (!connected) {
    startAccessPoint();
  }

  setupWebServer();
}

void loop() {
  ensureConnectivity();
  server.handleClient();
}
