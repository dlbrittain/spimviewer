package viewer.hdf5.img;

import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;

import viewer.hdf5.img.Hdf5ImgCells.CellCache;
import viewer.util.StopWatch;

public class Hdf5GlobalCellCache< A >
{
	final int numTimepoints;

	final int numSetups;

	final int maxNumLevels;

	protected class Key
	{
		final int timepoint;

		final int setup;

		final int level;

		final int index;

		public Key( final int timepoint, final int setup, final int level, final int index )
		{
			this.timepoint = timepoint;
			this.setup = setup;
			this.level = level;
			this.index = index;

			final long value = ( ( index * maxNumLevels + level ) * numSetups + setup ) * numTimepoints + timepoint;
			hashcode = ( int ) ( value ^ ( value >>> 32 ) );
		}

		@Override
		public boolean equals( final Object other )
		{
			if ( this == other )
				return true;
			if ( !( other instanceof Hdf5GlobalCellCache.Key ) )
				return false;
			@SuppressWarnings( "unchecked" )
			final Key that = ( Key ) other;
			return ( this.timepoint == that.timepoint ) && ( this.setup == that.setup ) && ( this.level == that.level ) && ( this.index == that.index );
		}

		final int hashcode;

		@Override
		public int hashCode()
		{
			return hashcode;
		}
	}

	protected class Entry
	{
		final protected Key key;

		final protected Hdf5Cell< A > data;

		public Entry( final Key key, final Hdf5Cell< A > data )
		{
			this.key = key;
			this.data = data;
		}

		public Hdf5Cell< A > getData()
		{
			return data;
		}

		@Override
		public void finalize()
		{
			synchronized ( softReferenceCache )
			{
				// System.out.println( "finalizing..." );
				softReferenceCache.remove( key );
				// System.out.println( softReferenceCache.size() +
				// " tiles chached." );
			}
		}
	}

	final protected ConcurrentHashMap< Key, SoftReference< Entry > > softReferenceCache = new ConcurrentHashMap< Key, SoftReference< Entry > >();

	final protected Hdf5ArrayLoader< A > loader;

	public Hdf5GlobalCellCache( final Hdf5ArrayLoader< A > loader, final int numTimepoints, final int numSetups, final int maxNumLevels )
	{
		this.loader = loader;
		this.numTimepoints = numTimepoints;
		this.numSetups = numSetups;
		this.maxNumLevels = maxNumLevels;
	}

	public Hdf5Cell< A > getGlobalIfCached( final int timepoint, final int setup, final int level, final int index )
	{
		final Key k = new Key( timepoint, setup, level, index );
		final SoftReference< Entry > ref = softReferenceCache.get( k );
		if ( ref != null )
		{
			final Entry entry = ref.get();
			if ( entry != null )
				return entry.data;
		}
		return null;
	}

	static class IoStatistics
	{
		final private StopWatch stopWatch;

		private long ioBytes;

		private boolean timeoutSet;

		private long timeout;

		private Runnable timeoutCallback;

		public IoStatistics()
		{
			stopWatch = new StopWatch();
			ioBytes = 0;
			timeout = -1;
			timeoutCallback = null;
		}

		public long getIoBytes()
		{
			return ioBytes;
		}

		public long getIoNanoTime()
		{
			return stopWatch.nanoTime();
		}

		public void setIoNanoTimeout( final long t, final Runnable callback )
		{
			timeoutSet = true;
			timeout = t;
			timeoutCallback = callback;
		}

		public void clearIoNanoTimeout()
		{
			timeoutSet = false;
		}

		public boolean timeoutReached()
		{
			return timeoutSet && ( timeout < getIoNanoTime() );
		}

		public void timeoutCallback()
		{
			if ( timeoutCallback != null )
			{
				timeoutCallback.run();
				timeoutCallback = null;
			}
		}
	}

	final static ConcurrentHashMap< ThreadGroup, IoStatistics > perThreadGroupIoStatistics = new ConcurrentHashMap< ThreadGroup, IoStatistics >();

	final static IoStatistics getThreadGroupIoStatistics()
	{
		final ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
		IoStatistics statistics = perThreadGroupIoStatistics.get( threadGroup );
		if ( statistics == null )
		{
			statistics = new IoStatistics();
			perThreadGroupIoStatistics.put( threadGroup, statistics );
		}
		return statistics;
	}

	public static void setThreadGroupIoNanoTimeout( final long t, final Runnable callback )
	{
		getThreadGroupIoStatistics().setIoNanoTimeout( t, callback );
	}

	public static void clearThreadGroupIoNanoTimeout()
	{
		getThreadGroupIoStatistics().clearIoNanoTimeout();
	}

	public static long getThreadGroupIoNanoTime()
	{
		return getThreadGroupIoStatistics().getIoNanoTime();
	}

	public static long getThreadGroupIoBytes()
	{
		return getThreadGroupIoStatistics().getIoBytes();
	}

	public synchronized Hdf5Cell< A > loadGlobal( final int[] cellDims, final long[] cellMin, final int timepoint, final int setup, final int level, final int index )
	{
		final Key k = new Key( timepoint, setup, level, index );
		final SoftReference< Entry > ref = softReferenceCache.get( k );
		if ( ref != null )
		{
			final Entry entry = ref.get();
			if ( entry != null )
				return entry.data;
		}

		final IoStatistics statistics = getThreadGroupIoStatistics();
		if ( statistics.timeoutReached() )
		{
			statistics.timeoutCallback();
			return new Hdf5Cell< A >( cellDims, cellMin, loader.emptyArray( cellDims ) );
		}

		statistics.stopWatch.start();
		final Hdf5Cell< A > cell = new Hdf5Cell< A >( cellDims, cellMin, loader.loadArray( timepoint, setup, level, cellDims, cellMin ) );
		softReferenceCache.put( k, new SoftReference< Entry >( new Entry( k, cell ) ) );
		statistics.stopWatch.stop();

		int c = loader.getBytesPerElement();
		for ( final int l : cellDims )
			c *= l;
		statistics.ioBytes += c;

		return cell;
	}

	public class Hdf5CellCache implements CellCache< A >
	{
		final int timepoint;

		final int setup;

		final int level;

		public Hdf5CellCache( final int timepoint, final int setup, final int level )
		{
			this.timepoint = timepoint;
			this.setup = setup;
			this.level = level;
		}

		@Override
		public Hdf5Cell< A > get( final int index )
		{
			return getGlobalIfCached( timepoint, setup, level, index );
		}

		@Override
		public Hdf5Cell< A > load( final int index, final int[] cellDims, final long[] cellMin )
		{
			return loadGlobal( cellDims, cellMin, timepoint, setup, level, index );
		}
	}
}
