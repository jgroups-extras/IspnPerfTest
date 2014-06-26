package org.perf;

import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.JChannel;

/**
 * @author Bela Ban
 * @since x.y
 */
public class CustomTransport extends JGroupsTransport {
    protected long initial_uuid;
    protected long uuid;


    protected void startJGroupsChannelIfNeeded() {
        if(uuid > 0)
            ((JChannel)channel).addAddressGenerator(new Test.OneTimeAddressGenerator(uuid));
        super.startJGroupsChannelIfNeeded();
    }

    public void setUUID(long uuid) {
        this.uuid=uuid;
    }
}
