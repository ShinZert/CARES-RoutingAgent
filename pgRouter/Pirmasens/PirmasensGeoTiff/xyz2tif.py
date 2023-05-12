
"""
Convert .xyz elevation models to GeoTiff
========================================
This gist, using highly optimized Python library Pandas does the conversion on the same computer in less then 1 second. 
The script parallizes the conversion of multiple files and translates 150 files á 1M lines in 17 seconds on 
16 core machine and a fast SSD. gdal_translate took 50 min.
Prerequisites:
--------------
$ pip install numpy pandas rasterio
Usage:
------
$ python3 xyz2tif.py *.xyz
To merge the resulting files into one raster
$ python3 xyz2tif.py merge *.tif

The compress option has been changed from lzw to none since AERMAP cannot read compressed GEOTIFF files. 
"""
import sys
import os
import multiprocessing
import time
import pandas as pd
import numpy as np
import rasterio as rio
from rasterio import transform as riotrans

CRS='EPSG:32632'

def load_xyz(fn: str) -> pd.DataFrame:
    """
    Loads a xyz data table from disk
    
    Parameters
    ----------
    fn : str
        File name
    """
    return pd.read_table(fn, names='x y z'.split(), sep=r'\s+', index_col=False)
    

def check_coords(xyz: pd.DataFrame):
    x_count = len(xyz['x'].unique())
    y_count = len(xyz['y'].unique())
    z_count = len(xyz['z'].unique())
    print(f'Distinct values: {x_count} x, {y_count} y and {z_count} z values')

def xyz2matrix(xyz: pd.DataFrame) -> (np.ndarray, float, float, float, float):
    """
    Converts the xyz dataframe to a 2d numpy array with origin and bounding box
    """
    mat = xyz.pivot_table(index='y', columns='x', values='z')
    mat.sort_index(axis='index', ascending=False, inplace=True)
    mat.sort_index(axis='columns', ascending=True, inplace=True)
    # Get origin (upper left corner)
    cellsize_x = mat.columns[1] - mat.columns[0]
    cellsize_y = mat.index[1] - mat.index[0]
    south = mat.index.min() - cellsize_y / 2
    north = mat.index.max() + cellsize_y / 2
    west = mat.columns.min() - cellsize_x / 2
    east = mat.columns.max() + cellsize_x / 2
       
    arr = np.asarray(mat, dtype=np.float32)
    
    return arr, west, south, east, north

def merge(outfile, *in_files):
    """
    Merges in_files (*.tif) to outfile
    """
    from rasterio.merge import merge as rmerge
    t0 = time.time()
    rasters = [rio.open(fn) for fn in in_files]
    t1 = time.time() -t0
    print(f'{t1:0.1f}s : Merging {len(rasters)} rasters')
    arr, transform = rmerge(rasters)
    t2 = time.time() - t0
    print(f'{t2:0.1f}s : Save {arr.shape[1]} x {arr.shape[2]} raster to {outfile}')
    with rio.open(
        outfile, 'w', 
        driver='GTiff',
        height= arr.shape[1], width = arr.shape[2],
        count=arr.shape[0], dtype=str(arr.dtype),
        crs=CRS, transform=transform, 
        compress='none', num_threads=os.cpu_count()
    ) as raster:
        raster.write(arr)
    t3 = time.time() - t0
    print(f'{t3:0.1f}s : Done')
    

def matrix2raster(fn_out:str, arr: np.ndarray, west: float, south: float, east: float, north: float):

    transform = riotrans.from_bounds(
        west=west, south=south,
        east=east, north=north,
        width=arr.shape[1],
        height=arr.shape[0]
    )
    
    with rio.open(
        fn_out, 'w', 
        driver='GTiff',
        height= arr.shape[0], width = arr.shape[1],
        count=1, dtype=str(arr.dtype),
        crs=CRS, transform=transform, compress='none'
    ) as raster:
        raster.write(arr, 1)
        
def process(fn_in, fn_out=None):
    xyz = load_xyz(fn_in)
    arr, west, south, east, north = xyz2matrix(xyz)
    fn_out = fn_out or fn_in.rsplit('.', 1)[0] + '.tif'
    matrix2raster(fn_out, arr, west, south, east, north)
    return fn_out

def multi_process(files, *, nproc=None):
    nproc = nproc or os.cpu_count()
    with multiprocessing.Pool(nproc) as pool:
        total = len(files)
        
        print(f'Running {total} files on {nproc} processes')
        t0 = time.time()
        for i, fn_out in enumerate(pool.imap_unordered(process, files)):
            et = time.time() - t0
            tt = et * total / (i + 1)
            print(f'{i}/{total} : {fn_out} {et:0.1f}s/{tt:0.1f}s')
    

if __name__ == '__main__':
    if len(sys.argv) == 1:
        sys.stderr.write('Usage for conversion:\n$ python xyz2tif.py *.xyz\nUsage for merging:\n$ python xyz2tif.py merge *.tif')
    elif 'merge' in sys.argv:
        sys.argv.remove('merge')
        merge('all_merge.tif', *sys.argv[1:])
    else:
        multi_process(sys.argv[1:])
        