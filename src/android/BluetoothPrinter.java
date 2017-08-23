package com.ru.cordova.printer.bluetooth;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Set;
import java.util.UUID;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Bitmap.Config;
import android.util.Xml.Encoding;
import android.util.Base64;

public class BluetoothPrinter extends CordovaPlugin {
	private static final String LOG_TAG = "BluetoothPrinter";
	BluetoothAdapter mBluetoothAdapter;
	BluetoothSocket mmSocket;
	BluetoothDevice mmDevice;
	OutputStream mmOutputStream;
	InputStream mmInputStream;
	Thread workerThread;
	byte[] readBuffer;
	int readBufferPosition;
	int counter;
	volatile boolean stopWorker;

	Bitmap bitmap;


	public Canvas canvas = null;
	public Paint paint = null;
	public Bitmap bm = null;
	public int width;
	public float length = 0.0F;
	public byte[] bitbuf = null;



	public BluetoothPrinter() {}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		if (action.equals("list")) {
			listBT(callbackContext);
			return true;
		} else if (action.equals("connect")) {
			String name = args.getString(0);
			if (findBT(callbackContext, name)) {
				try {
					connectBT(callbackContext);
				} catch (IOException e) {
					Log.e(LOG_TAG, e.getMessage());
					e.printStackTrace();
				}
			} else {
				callbackContext.error("Bluetooth Device Not Found: " + name);
			}
			return true;
		} else if (action.equals("disconnect")) {
            try {
                disconnectBT(callbackContext);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        }
    else if (action.equals("print") || action.equals("printImage")) {
			try {
				String msg = args.getString(0);
				printImage(callbackContext, msg);
			} catch (IOException e) {
				Log.e(LOG_TAG, e.getMessage());
				e.printStackTrace();
			}
			return true;
		}
		else if (action.equals("printText")) {
    			try {
    				String msg = args.getString(0);
    				printText(callbackContext, msg);
    			} catch (IOException e) {
    				Log.e(LOG_TAG, e.getMessage());
    				e.printStackTrace();
    			}
    			return true;
    		}
        else if (action.equals("printPOSCommand")) {
			try {
				String msg = args.getString(0);
                printPOSCommand(callbackContext, hexStringToBytes(msg));
			} catch (IOException e) {
				Log.e(LOG_TAG, e.getMessage());
				e.printStackTrace();
			}
			return true;
		}
		return false;
	}

    //This will return the array list of paired bluetooth printers
	void listBT(CallbackContext callbackContext) {
		BluetoothAdapter mBluetoothAdapter = null;
		String errMsg = null;
		try {
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mBluetoothAdapter == null) {
				errMsg = "No bluetooth adapter available";
				Log.e(LOG_TAG, errMsg);
				callbackContext.error(errMsg);
				return;
			}
			if (!mBluetoothAdapter.isEnabled()) {
				Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				this.cordova.getActivity().startActivityForResult(enableBluetooth, 0);
			}
			Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
			if (pairedDevices.size() > 0) {
				JSONArray json = new JSONArray();
				for (BluetoothDevice device : pairedDevices) {
					/*
					Hashtable map = new Hashtable();
					map.put("type", device.getType());
					map.put("address", device.getAddress());
					map.put("name", device.getName());
					JSONObject jObj = new JSONObject(map);
					*/
					json.put(device.getName());
				}
				callbackContext.success(json);
			} else {
				callbackContext.error("No Bluetooth Device Found");
			}
			//Log.d(LOG_TAG, "Bluetooth Device Found: " + mmDevice.getName());
		} catch (Exception e) {
			errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
	}

	// This will find a bluetooth printer device
	boolean findBT(CallbackContext callbackContext, String name) {
		try {
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mBluetoothAdapter == null) {
				Log.e(LOG_TAG, "No bluetooth adapter available");
			}
			if (!mBluetoothAdapter.isEnabled()) {
				Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				this.cordova.getActivity().startActivityForResult(enableBluetooth, 0);
			}
			Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
			if (pairedDevices.size() > 0) {
				for (BluetoothDevice device : pairedDevices) {
					if (device.getName().equalsIgnoreCase(name)) {
						mmDevice = device;
						return true;
					}
				}
			}
			Log.d(LOG_TAG, "Bluetooth Device Found: " + mmDevice.getName());
		} catch (Exception e) {
			String errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
		return false;
	}

	// Tries to open a connection to the bluetooth printer device
	boolean connectBT(CallbackContext callbackContext) throws IOException {
		try {
			// Standard SerialPortService ID
			UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
			mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
			mmSocket.connect();
			mmOutputStream = mmSocket.getOutputStream();
			mmInputStream = mmSocket.getInputStream();
			beginListenForData();
			//Log.d(LOG_TAG, "Bluetooth Opened: " + mmDevice.getName());
			callbackContext.success("Bluetooth Opened: " + mmDevice.getName());
			return true;
		} catch (Exception e) {
			String errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
		return false;
	}

	// After opening a connection to bluetooth printer device,
	// we have to listen and check if a data were sent to be printed.
	void beginListenForData() {
		try {
			final Handler handler = new Handler();
			// This is the ASCII code for a newline character
			final byte delimiter = 10;
			stopWorker = false;
			readBufferPosition = 0;
			readBuffer = new byte[1024];
			workerThread = new Thread(new Runnable() {
				public void run() {
					while (!Thread.currentThread().isInterrupted() && !stopWorker) {
						try {
							int bytesAvailable = mmInputStream.available();
							if (bytesAvailable > 0) {
								byte[] packetBytes = new byte[bytesAvailable];
								mmInputStream.read(packetBytes);
								for (int i = 0; i < bytesAvailable; i++) {
									byte b = packetBytes[i];
									if (b == delimiter) {
										byte[] encodedBytes = new byte[readBufferPosition];
										System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
										/*
										final String data = new String(encodedBytes, "US-ASCII");
										readBufferPosition = 0;
										handler.post(new Runnable() {
											public void run() {
												myLabel.setText(data);
											}
										});
                                        */
									} else {
										readBuffer[readBufferPosition++] = b;
									}
								}
							}
						} catch (IOException ex) {
							stopWorker = true;
						}
					}
				}
			});
			workerThread.start();
		} catch (NullPointerException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	//This will send data to bluetooth printer
	boolean printText(CallbackContext callbackContext, String msg) throws IOException {
		try {

			mmOutputStream.write(msg.getBytes());

			// tell the user data were sent
			//Log.d(LOG_TAG, "Data Sent");
			callbackContext.success("Data Sent");
			return true;

		} catch (Exception e) {
			String errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
		return false;
	}

	//This will send data to bluetooth printer
    boolean printImage(CallbackContext callbackContext, String msg) throws IOException {
        try {

            final String encodedString = msg;
            final String pureBase64Encoded = encodedString.substring(encodedString.indexOf(",")  + 1);

            final byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);

            Bitmap decodedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            bitmap = decodedBitmap;

 			this.initCanvas(384);
			this.initPaint();
			this.drawImage(0.0f,0.0f,bitmap);

            byte[]  bt =getBitmapData(bitmap);

            bitmap.recycle();

            mmOutputStream.write(bt);

            // tell the user data were sent
            //Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;


        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }


    boolean printPOSCommand(CallbackContext callbackContext, byte[] buffer) throws IOException {
        try {
            //mmOutputStream.write(("Inam").getBytes());
            //mmOutputStream.write((((char)0x0A) + "10 Rehan").getBytes());
            mmOutputStream.write(buffer);
            //mmOutputStream.write(0x0A);

            // tell the user data were sent
			Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

	// disconnect bluetooth printer.
	boolean disconnectBT(CallbackContext callbackContext) throws IOException {
		try {
			stopWorker = true;
			mmOutputStream.close();
			mmInputStream.close();
			mmSocket.close();
			callbackContext.success("Bluetooth Disconnect");
			return true;
		} catch (Exception e) {
			String errMsg = e.getMessage();
			Log.e(LOG_TAG, errMsg);
			e.printStackTrace();
			callbackContext.error(errMsg);
		}
		return false;
	}


	public byte[] getText(String textStr) {
        // TODO Auto-generated method stubbyte[] send;
        byte[] send=null;
        try {
            send = textStr.getBytes("GBK");
        } catch (UnsupportedEncodingException e) {
            send = textStr.getBytes();
        }
        return send;
    }

    public static byte[] hexStringToBytes(String hexString) {
        hexString = hexString.toLowerCase();
        String[] hexStrings = hexString.split(" ");
        byte[] bytes = new byte[hexStrings.length];
        for (int i = 0; i < hexStrings.length; i++) {
            char[] hexChars = hexStrings[i].toCharArray();
            bytes[i] = (byte) (charToByte(hexChars[0]) << 4 | charToByte(hexChars[1]));
        }
        return bytes;
    }

    private static byte charToByte(char c) {
		return (byte) "0123456789abcdef".indexOf(c);
	}




	public int getLength() {
		return (int)this.length + 20;
	}


	public byte[] getBitmapData(Bitmap bitmap) {

		Bitmap nbm = Bitmap.createBitmap(this.bm, 0, 0, this.width, this.getLength());
		byte[] imgbuf = new byte[this.width / 8 * this.getLength() + 8];
		boolean s = false;
		imgbuf[0] = 29;
		imgbuf[1] = 118;
		imgbuf[2] = 48;
		imgbuf[3] = 0;
		imgbuf[4] = (byte)(this.width / 8);
		imgbuf[5] = 0;
		imgbuf[6] = (byte)(this.getLength() % 256);
		imgbuf[7] = (byte)(this.getLength() / 256);
		int var23 = 7;

		for(int i = 0; i < this.getLength(); ++i) {
			int t;
			for(t = 0; t < this.width / 8; ++t) {
				int c0 = nbm.getPixel(t * 8 + 0, i);
				byte p0;
				if(c0 == -1) {
					p0 = 0;
				} else {
					p0 = 1;
				}

				int c1 = nbm.getPixel(t * 8 + 1, i);
				byte p1;
				if(c1 == -1) {
					p1 = 0;
				} else {
					p1 = 1;
				}

				int c2 = nbm.getPixel(t * 8 + 2, i);
				byte p2;
				if(c2 == -1) {
					p2 = 0;
				} else {
					p2 = 1;
				}

				int c3 = nbm.getPixel(t * 8 + 3, i);
				byte p3;
				if(c3 == -1) {
					p3 = 0;
				} else {
					p3 = 1;
				}

				int c4 = nbm.getPixel(t * 8 + 4, i);
				byte p4;
				if(c4 == -1) {
					p4 = 0;
				} else {
					p4 = 1;
				}

				int c5 = nbm.getPixel(t * 8 + 5, i);
				byte p5;
				if(c5 == -1) {
					p5 = 0;
				} else {
					p5 = 1;
				}

				int c6 = nbm.getPixel(t * 8 + 6, i);
				byte p6;
				if(c6 == -1) {
					p6 = 0;
				} else {
					p6 = 1;
				}

				int c7 = nbm.getPixel(t * 8 + 7, i);
				byte p7;
				if(c7 == -1) {
					p7 = 0;
				} else {
					p7 = 1;
				}

				int value = p0 * 128 + p1 * 64 + p2 * 32 + p3 * 16 + p4 * 8 + p5 * 4 + p6 * 2 + p7;
				this.bitbuf[t] = (byte)value;
			}

			for(t = 0; t < this.width / 8; ++t) {
				++var23;
				imgbuf[var23] = this.bitbuf[t];
			}
		}

		return imgbuf;

	}



	public void initCanvas(int w) {
		int h = 10 * w;
		this.bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_4444);
		this.canvas = new Canvas(this.bm);
		this.canvas.drawColor(-1);
		this.width = w;
		this.bitbuf = new byte[this.width / 8];
	}

	public void initPaint() {
		this.paint = new Paint();
		this.paint.setAntiAlias(true);
		this.paint.setColor(-16777216);
		this.paint.setStyle(Paint.Style.STROKE);
	}

	public void drawImage(float x, float y, Bitmap bitmap) {
		try {
			this.canvas.drawBitmap(bitmap, x, y, (Paint)null);
			if (this.length < ((float) bitmap.getHeight()) + y) {
				this.length = (((float) bitmap.getHeight()) + y) - 20.0f;
			}
		} catch (Exception var5) {
			var5.printStackTrace();
		}

	}



	public byte[] getImage(Bitmap bitmap) {
        // TODO Auto-generated method stub
        int mWidth = bitmap.getWidth();
        int mHeight = bitmap.getHeight();
        bitmap=resizeImage(bitmap, 48 * 8, mHeight);
        //bitmap=resizeImage(bitmap, imageWidth * 8, mHeight);
        /*
        mWidth = bitmap.getWidth();
        mHeight = bitmap.getHeight();
        int[] mIntArray = new int[mWidth * mHeight];
        bitmap.getPixels(mIntArray, 0, mWidth, 0, 0, mWidth, mHeight);
        byte[]  bt =getBitmapData(mIntArray, mWidth, mHeight);*/

        byte[]  bt =getBitmapData(bitmap);


        /*try {//?????????????????
            createFile("/sdcard/demo.txt",bt);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }*/


        ////byte[]  bt =StartBmpToPrintCode(bitmap);

        bitmap.recycle();
        return bt;
    }

    private static Bitmap resizeImage(Bitmap bitmap, int w, int h) {
        Bitmap BitmapOrg = bitmap;
        int width = BitmapOrg.getWidth();
        int height = BitmapOrg.getHeight();

        if(width>w)
        {
            float scaleWidth = ((float) w) / width;
            float scaleHeight = ((float) h) / height+24;
            Matrix matrix = new Matrix();
            matrix.postScale(scaleWidth, scaleWidth);
            Bitmap resizedBitmap = Bitmap.createBitmap(BitmapOrg, 0, 0, width,
                    height, matrix, true);
            return resizedBitmap;
        }else{
            Bitmap resizedBitmap = Bitmap.createBitmap(w, height+24, Config.RGB_565);
            Canvas canvas = new Canvas(resizedBitmap);
            Paint paint = new Paint();
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(bitmap, (w-width)/2, 0, paint);
            return resizedBitmap;
        }
    }

	public static byte[] encodeYUV420SP(int[] rgba, int width, int height) {
		final int frameSize = width * height;
		byte[] yuv420sp=new byte[frameSize];
		int[] U, V;
		U = new int[frameSize];
		V = new int[frameSize];
		final int uvwidth = width / 2;
		int r, g, b, y, u, v;
		int bits = 8;
		int index = 0;
		int f = 0;
		for (int j = 0; j < height; j++) {
			for (int i = 0; i < width; i++) {
				r = (rgba[index] & 0xff000000) >> 24;
				g = (rgba[index] & 0xff0000) >> 16;
				b = (rgba[index] & 0xff00) >> 8;
				// rgb to yuv
				y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
				u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
				v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
				// clip y
				// yuv420sp[index++] = (byte) ((y < 0) ? 0 : ((y > 255) ? 255 :
				// y));
				byte temp = (byte) ((y < 0) ? 0 : ((y > 255) ? 255 : y));
				yuv420sp[index++] = temp > 0 ? (byte) 1 : (byte) 0;

				// {
				// if (f == 0) {
				// yuv420sp[index++] = 0;
				// f = 1;
				// } else {
				// yuv420sp[index++] = 1;
				// f = 0;
				// }

				// }

			}

		}
		f = 0;
		return yuv420sp;
	}


}
