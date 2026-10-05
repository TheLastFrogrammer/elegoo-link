package io.github.thelastfrogrammer.elink;

/** Identity lookup is independent of MQTT authentication. No HTTP dependency when discovery succeeds. */
public final class PrinterIdentity implements Cc2Session.IdentityResolver {
    private final String manual;
    private final Cc2Discovery discovery;
    private Cc2Discovery.Info info;
    private boolean mismatch;
    public PrinterIdentity(String manual, Cc2Discovery discovery) {
        this.manual = manual == null ? "" : manual.trim(); this.discovery = discovery;
        if (!this.manual.isEmpty() && !Cc2Discovery.validSerial(this.manual)) throw new IllegalArgumentException("Invalid serial number");
    }
    public String resolve(PrinterHttp http) throws Exception {
        try {
            info = discovery.discoverInfo(http.host());
            mismatch = !manual.isEmpty() && !manual.equals(info.serial);
            return info.serial;
        }
        catch (java.io.IOException ignored) {
            if (!manual.isEmpty()) return manual;
            try { return http.systemInfo().getString("sn"); }
            catch (Exception error) {
                if (error instanceof PrinterErrors.Rejected || (error instanceof PrinterErrors.HttpStatus
                    && (((PrinterErrors.HttpStatus) error).status == 401 || ((PrinterErrors.HttpStatus) error).status == 403))) throw error;
                throw new PrinterErrors.IdentityUnavailable();
            }
        }
    }
    public String password(PrinterHttp http) throws Exception {
        if (info != null && Boolean.FALSE.equals(info.lanOnly)) throw new PrinterErrors.CloudMode();
        return info != null && Boolean.FALSE.equals(info.codeProtected) ? "123456" : http.token();
    }
    public String summary() {
        if (info == null) return "Printer mode and code-protection state were not available from UDP discovery.";
        return info.summary() + (mismatch ? " Manual serial differed; using the discovered printer serial." : "");
    }
    @Override public void close() { discovery.close(); }
}
