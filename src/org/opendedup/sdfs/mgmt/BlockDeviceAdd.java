package org.opendedup.sdfs.mgmt;

import java.io.File;
import java.io.IOException;

import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.io.BlockDev;
import org.opendedup.util.StringUtils;
import org.w3c.dom.Element;

public class BlockDeviceAdd {
	
	public Element getResult(String devName,String size,String start) throws Exception {
		if(!Main.blockDev)
			throw new IOException("Block devices not supported on this volume");
		else {
			long sz = StringUtils.parseSize(size);
			BlockDev dev = new BlockDev(devName,null,Main.volume.getPath() + File.separator +devName,sz,Boolean.parseBoolean(start),null);
			Main.volume.addBlockDev(dev);
			return dev.getElement();
		}
	}

}
