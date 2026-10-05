package io.github.thelastfrogrammer.elink;

/** Identity lookup is independent of MQTT authentication. No HTTP dependency when discovery succeeds. */
public final class PrinterIdentity implements Cc2Session.IdentityResolver {
    private final String manual;
    private final Cc2Discovery discovery;
    public PrinterIdentity(String manual, Cc2Discovery discovery) {
        this.manual = manual == null ? "" : manual.trim(); this.discovery = discovery;
        if (!this.manual.isEmpty() && !Cc2Discovery.validSerial(this.manual)) throw new IllegalArgumentException("Invalid serial number");
    }
    public String resolve(PrinterHttp http) throws Exception {
        if (!manual.isEmpty()) return manual;
        try { return discovery.discover(http.host()); }
        catch (java.io.IOException ignored) {
            try { return http.systemInfo().getString("sn"); }
            catch (Exception error) {
                if (error instanceof PrinterErrors.Rejected || (error instanceof PrinterErrors.HttpStatus
                    && (((PrinterErrors.HttpStatus) error).status == 401 || ((PrinterErrors.HttpStatus) error).status == 403))) throw error;
                throw new PrinterErrors.IdentityUnavailable();
            }
        }
    }
    @Override public void close() { discovery.close(); }
}
