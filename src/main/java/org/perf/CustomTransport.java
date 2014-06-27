package org.perf;

import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.JChannel;
import org.jgroups.util.OneTimeAddressGenerator;

/**
 * @author Bela Ban
 * @since x.y
 */
public class CustomTransport extends JGroupsTransport {
    protected long initial_uuid;
    protected long uuid;


    protected void startJGroupsChannelIfNeeded() {
        if(uuid > 0)
            ((JChannel)channel).addAddressGenerator(new OneTimeAddressGenerator(uuid));
        super.startJGroupsChannelIfNeeded();
    }

    public void setUUID(long uuid) {
        this.uuid=uuid;
    }
}
