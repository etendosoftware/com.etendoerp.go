FROM jsreport/jsreport:4.7.0

# Install Chromium for chrome-pdf recipe (required on ARM64 where image lacks Chrome)
USER root
RUN apk add --no-cache chromium
RUN mkdir -p /app/data && chown -R jsreport:jsreport /app/data
# QR code generation in templates (used by helpers via require('qrcode'))
RUN cd /app && npm install --save qrcode
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser
USER jsreport

COPY jsreport.config.json /app/jsreport.config.json

EXPOSE 5488
