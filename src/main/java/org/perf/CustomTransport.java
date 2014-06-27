package org.perf;

import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.JChannel;
import org.jgroups.util.OneTimeAddressGenerator;

/**
 * @author Bela Ban
 * @since x.y
 */
public class CustomTransport extends JGroupsTransport {
    protected long   uuid;
    protected String logical_name;


    protected void startJGroupsChannelIfNeeded() {
        if(uuid > 0)
            ((JChannel)channel).addAddressGenerator(new OneTimeAddressGenerator(uuid));
        super.startJGroupsChannelIfNeeded();
    }

    @Override
    protected void initChannel() {
        super.initChannel();
        if(logical_name != null)
            channel.setName(logical_name);
    }

    public void setUUID(long uuid) {
        this.uuid=uuid;
    }

    public void setLogicalName(String logical_name) {
        this.logical_name=logical_name;
    }
}
